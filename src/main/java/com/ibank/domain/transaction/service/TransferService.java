package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionService;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.outbox.DomainEvents;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.Channel;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.exception.SameAccountTransferException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionProcessor.Outcome;
import com.ibank.domain.transaction.service.TransactionProcessor.Reservation;
import com.ibank.global.audit.Audited;
import com.ibank.global.metrics.IbankMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 거래 요청의 진입점.
 *
 * <p>이체와 출금은 <b>2단계 처리</b>를 탄다. 이 클래스는 세 단계를 순서대로 부르는 조율만 하고
 * 트랜잭션은 열지 않는다. 각 단계의 트랜잭션은 {@link TransactionProcessor}가 따로 연다.
 *
 * <pre>
 * 1) 가승인  — 짧은 트랜잭션, 계좌 락 보유. 거래 PENDING_REVIEW + 홀드 ACTIVE 저장 후 커밋
 * 2) 판정    — 락도 DB 커넥션도 쥐지 않은 상태에서 판정 포트 호출 (외부 HTTP일 수 있다)
 * 3) 확정    — 짧은 트랜잭션, 락 재획득. APPROVE면 자금 이동, HOLD면 보류
 * </pre>
 *
 * <p>판정을 락 안에서 기다리지 않는 이유: 판정 서비스가 느려지면 그동안 출금 계좌의 행 락과 커넥션이
 * 묶여, 같은 계좌의 다른 거래와 커넥션 풀 전체가 함께 멈춘다. 대신 금액을 홀드로 묶어 두므로,
 * 판정을 기다리는 동안에도 같은 돈이 다른 출금으로 빠져나가지 못한다.
 *
 * <p>입금은 판정 대상이 아니므로 한 트랜잭션으로 바로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    /** 3단계가 락 대기·커넥션 획득 실패로 넘어졌을 때의 재시도 횟수. */
    private static final int APPLY_ATTEMPTS = 3;
    private static final long APPLY_RETRY_BACKOFF_MS = 100;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionLimitService transactionLimitService;
    private final TransactionProcessor processor;
    private final FraudDecisionService fraudDecisionService;
    private final IdempotencyGuard idempotencyGuard;
    private final IbankMetrics metrics;
    private final DomainEvents events;

    // ------------------------------------------------------------------
    // 이체·출금: 2단계 처리
    // ------------------------------------------------------------------

    /**
     * 이체.
     *
     * 소유권:     출금 계좌(fromAccount)는 반드시 요청자 본인 소유여야 한다. 타인 계좌 출금을 차단한다.
     * 데드락 방지: account_number 오름차순으로 락 획득 (전역 락 순서 고정).
     * 멱등성:     락 획득 전 1차 검증, 락 획득 후 2차 재검증. 같은 키로 재요청하면 거래의 현재 상태
     *             (PENDING_REVIEW / HELD / COMPLETED 등)를 그대로 돌려준다.
     *
     * {@code Propagation.NEVER}: 바깥 트랜잭션 안에서 호출되면 세 단계가 한 트랜잭션으로 합쳐져
     * 락을 쥔 채 판정을 기다리게 된다. 그런 호출은 예외로 막는다.
     */
    @Audited(action = "TRANSFER", target = "#request.fromAccountNumber + '→' + #request.toAccountNumber")
    @Transactional(propagation = Propagation.NEVER)
    public TransferResponse transfer(Long userId, TransferRequest request) {
        // 동일 계좌 이체 차단: 락/멱등성 키 소모·무의미한 거래·원장 leg 생성 전에 fail-fast
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new SameAccountTransferException(request.fromAccountNumber());
        }
        // 단건 한도: DB 접근 없는 검증이므로 락 획득 전에 fail-fast
        transactionLimitService.validateAmount(request.amount());

        // 요청 지문: 같은 멱등성 키가 다른 금액/계좌에 재사용되면 충돌로 거부하기 위함
        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.TRANSFER.name(),
                request.fromAccountNumber(),
                request.toAccountNumber(),
                IdempotencyFingerprint.normalizeAmount(request.amount()));

        Reservation reservation = processor.reserveTransfer(userId, request, fingerprint, Channel.MOBILE);
        return reservation.isReplay() ? reservation.replay() : decideAndApply(reservation);
    }

    /** 출금. 이체와 같은 2단계 처리를 탄다. */
    @Audited(action = "WITHDRAW", target = "#request.accountNumber")
    @Transactional(propagation = Propagation.NEVER)
    public TransferResponse withdraw(Long userId, WithdrawRequest request) {
        transactionLimitService.validateAmount(request.amount());

        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.WITHDRAWAL.name(),
                request.accountNumber(),
                IdempotencyFingerprint.normalizeAmount(request.amount()));

        Reservation reservation = processor.reserveWithdrawal(userId, request, fingerprint, Channel.MOBILE);
        return reservation.isReplay() ? reservation.replay() : decideAndApply(reservation);
    }

    /** 2단계(판정)와 3단계(확정). 이 시점에 이 스레드는 락도 트랜잭션도 쥐고 있지 않다. */
    private TransferResponse decideAndApply(Reservation reservation) {
        Decision decision = fraudDecisionService.decide(reservation.evaluation());
        Outcome outcome = applyWithRetry(reservation.transactionId(), decision);
        if (outcome.failure() != null) {
            // 거래는 FAILED로 커밋됐다. 실패 사유를 그대로 클라이언트에 전달한다.
            throw outcome.failure();
        }
        return outcome.response();
    }

    /**
     * 3단계를 실행하되, 락 대기 시간 초과나 커넥션 획득 실패 같은 일시적 실패는 몇 번 다시 시도한다.
     *
     * <p>1단계가 이미 커밋돼 금액이 묶여 있으므로, 여기서 포기하면 거래가 PENDING_REVIEW로 남는다.
     * 그 경우에도 돈은 움직이지 않았고 복구 배치가 뒤에 처리하지만, 흔한 일시적 실패 때문에
     * 고객의 돈이 묶여 있는 시간을 늘릴 이유가 없다. {@code applyDecision}은 거래 상태를 확인한 뒤에만
     * 바꾸므로 여러 번 불러도 안전하다.
     */
    private Outcome applyWithRetry(Long transactionId, Decision decision) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= APPLY_ATTEMPTS; attempt++) {
            try {
                return processor.applyDecision(transactionId, decision);
            } catch (PessimisticLockingFailureException | CannotCreateTransactionException e) {
                last = e;
                log.warn("거래 확정 일시 실패 (transactionId={}, 시도 {}/{}): {}",
                        transactionId, attempt, APPLY_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt < APPLY_ATTEMPTS) {
                    sleep(APPLY_RETRY_BACKOFF_MS * attempt);
                }
            }
        }
        throw last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 입금: 판정 대상이 아니므로 한 트랜잭션으로 처리
    // ------------------------------------------------------------------

    /**
     * HTTP 입금 — 소유권 검증 + 비관적 락.
     * 사용자 요청은 단건이므로 재시도 복잡성 없이 비관적 락으로 단순하게 처리.
     */
    @Audited(action = "DEPOSIT", target = "#request.accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse depositForUser(Long userId, DepositRequest request) {
        transactionLimitService.validateAmount(request.amount());

        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.DEPOSIT.name(),
                request.accountNumber(),
                IdempotencyFingerprint.normalizeAmount(request.amount()));

        Optional<Transaction> existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.accountNumber());
        }

        existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        account.ensureCanReceive();
        account.deposit(request.amount());

        Transaction transaction = Transaction.builder()
                .toAccount(account)
                .amount(request.amount())
                .type(Transaction.TransactionType.DEPOSIT)
                .description(request.description())
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        transaction.complete();

        transactionRepository.save(transaction);
        // 복식부기: 고객 CREDIT + 클리어링 DEBIT (외부 유입 자금의 상대 leg)
        ledgerService.recordDeposit(transaction, account, request.amount());
        events.deposit(transaction, account);
        return TransferResponse.from(transaction);
    }

    /**
     * 입금 처리 - 낙관적 락 사용.
     * 충돌 시 @Retryable이 재시도. 동시 입금이 많지만 충돌 확률이 낮을 때 적합.
     *
     * <p>락이 없어 동시 요청 두 건이 멱등성 선검사를 모두 통과한 뒤 같은 키로 insert하면
     * {@code transactions.idempotency_key} unique 제약 위반({@link DataIntegrityViolationException})이
     * 발생할 수 있다. 이 경우 같은 트랜잭션에서 복구할 수 없으므로(제약 위반으로 트랜잭션이 오염됨)
     * 재시도 대상에 포함한다 — 새 트랜잭션의 멱등성 선검사가 그사이 커밋된 거래를 발견해 replay로 수렴한다.
     */
    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class,
                DataIntegrityViolationException.class},
        maxAttempts = 5,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 1000)
    )
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deposit(String accountNumber, BigDecimal amount, String idempotencyKey) {
        transactionLimitService.validateAmount(amount);

        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.DEPOSIT.name(),
                accountNumber,
                IdempotencyFingerprint.normalizeAmount(amount));

        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            // 사용자 맥락이 없는 내부 호출 경로라 소유권은 보지 않고 지문만 확인한다
            if (!fingerprint.equals(existing.get().getRequestFingerprint())) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            metrics.countIdempotentReplay();
            return;
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        account.ensureCanReceive();
        account.deposit(amount);

        Transaction transaction = Transaction.builder()
                .toAccount(account)
                .amount(amount)
                .type(Transaction.TransactionType.DEPOSIT)
                .build()
                .withIdempotency(idempotencyKey, fingerprint);
        transaction.complete();

        transactionRepository.save(transaction);
        ledgerService.recordDeposit(transaction, account, amount);
        events.deposit(transaction, account);
    }
}
