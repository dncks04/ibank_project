package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.fraud.service.FraudDetectionService;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.exception.SameAccountTransferException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.audit.Audited;
import com.ibank.global.metrics.IbankMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionLimitService transactionLimitService;
    private final FraudDetectionService fraudDetectionService;
    private final FraudAlertRepository fraudAlertRepository;
    private final IbankMetrics metrics;

    /**
     * 멱등성 키로 기존 거래를 조회해 replay 여부를 결정한다.
     *
     * - 기존 거래가 없으면 {@code Optional.empty()} (정상 처리 진행).
     * - 기존 거래가 있고 요청 지문이 일치하면 기존 결과를 반환(진정한 중복 요청 → replay).
     * - 기존 거래가 있으나 지문이 불일치하면 같은 키가 다른 내용에 재사용된 것이므로
     *   {@link IdempotencyKeyConflictException}(409)로 거부한다. 옛 결과를 조용히 반환하지 않는다.
     */
    private Optional<TransferResponse> replayIfPresent(String idempotencyKey, String fingerprint) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> {
                    verifyFingerprint(tx, idempotencyKey, fingerprint);
                    metrics.countIdempotentReplay();
                    return TransferResponse.from(tx);
                });
    }

    /** 저장된 거래의 지문과 요청 지문이 다르면 멱등성 키 충돌로 간주한다. */
    private void verifyFingerprint(Transaction existing, String idempotencyKey, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
    }

    /**
     * 이체 처리 - 비관적 락 사용.
     *
     * 소유권:     출금 계좌(fromAccount)는 반드시 요청자 본인 소유여야 한다. 타인 계좌 출금을 차단한다.
     * 데드락 방지: account_number 오름차순으로 락 획득 (전역 락 순서 고정).
     * 멱등성:     락 획득 전 1차 검증, 락 획득 후 2차 재검증 (double-checked locking).
     *             READ_COMMITTED에서 락 해제 후 상대 스레드가 커밋된 행을 볼 수 있음을 이용.
     */
    @Audited(action = "TRANSFER", target = "#request.fromAccountNumber + '→' + #request.toAccountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
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

        // 1차 검증 (락 없음): 명백한 중복 요청 빠른 반환
        Optional<TransferResponse> existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 데드락 방지: 항상 계좌 번호 오름차순으로 락 획득
        List<String> sorted = List.of(request.fromAccountNumber(), request.toAccountNumber())
                .stream().sorted().toList();

        Account first = accountRepository.findByAccountNumberWithLock(sorted.get(0))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sorted.get(1))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(1)));

        // 2차 검증 (락 보유 상태): 동시 요청이 동일 key로 락을 대기 후 진입한 경우 방어
        // READ_COMMITTED이므로 락 대기 중 상대 스레드가 커밋하면 이 시점에 보임
        existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Account fromAccount = first.getAccountNumber().equals(request.fromAccountNumber()) ? first : second;
        Account toAccount   = first.getAccountNumber().equals(request.toAccountNumber())   ? first : second;

        // 소유권 검증: 출금 계좌는 반드시 요청자 본인 소유여야 한다 (타인 계좌 출금 차단)
        if (!fromAccount.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.fromAccountNumber());
        }

        // 1일 한도: 출금 계좌 락 보유 상태에서 검증 (동시 요청이 잔여 한도를 나눠 우회하지 못함)
        transactionLimitService.validateDailyOutflow(fromAccount, request.amount());

        // 이상거래 탐지: 락 보유 상태에서 평가 (velocity 카운트가 동시 요청에 흔들리지 않음).
        // 룰에 걸리면 자금을 옮기지 않고 보류(HELD)하며 경보를 남긴다.
        List<String> triggeredRules = fraudDetectionService.evaluate(
                fromAccount, toAccount, request.amount(), java.time.LocalDateTime.now());
        if (!triggeredRules.isEmpty()) {
            return holdSuspiciousTransfer(request, fromAccount, toAccount, fingerprint, triggeredRules);
        }

        fromAccount.withdraw(request.amount());
        toAccount.deposit(request.amount());

        Transaction transaction = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(request.amount())
                .type(Transaction.TransactionType.TRANSFER)
                .description(request.description())
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        transaction.complete();

        transactionRepository.save(transaction);
        // 복식부기: 출금 계좌 DEBIT + 입금 계좌 CREDIT (합이 0인 분개)
        ledgerService.recordTransfer(transaction, fromAccount, toAccount, request.amount());
        return TransferResponse.from(transaction);
    }

    /**
     * 이상거래로 판정된 이체를 보류한다. 자금을 옮기지 않고 거래를 HELD로 저장하며 경보를 남긴다.
     * 원장 항목을 만들지 않으므로(잔액 불변) 정산 불변식에 영향이 없다.
     * 멱등성 키는 HELD 거래가 소비하므로, 같은 요청을 재시도하면 보류된 결과가 그대로 반환된다.
     */
    private TransferResponse holdSuspiciousTransfer(TransferRequest request, Account fromAccount,
                                                    Account toAccount, String fingerprint,
                                                    List<String> triggeredRules) {
        Transaction held = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(request.amount())
                .type(Transaction.TransactionType.TRANSFER)
                .description(request.description())
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        held.hold();
        transactionRepository.save(held);

        fraudAlertRepository.save(FraudAlert.of(
                fromAccount.getId(), held.getId(), triggeredRules, request.amount(),
                "이상거래 탐지로 이체 보류: " + String.join(",", triggeredRules)));
        metrics.countOperation("TRANSFER_HELD", "SUCCESS");
        return TransferResponse.from(held);
    }

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

        Optional<TransferResponse> existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.accountNumber());
        }

        existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

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
        return TransferResponse.from(transaction);
    }

    /**
     * 출금 — 소유권 검증 + 비관적 락.
     * 잔액 초과 출금 방지를 위해 반드시 비관적 락 사용.
     */
    @Audited(action = "WITHDRAW", target = "#request.accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse withdraw(Long userId, WithdrawRequest request) {
        transactionLimitService.validateAmount(request.amount());

        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.WITHDRAWAL.name(),
                request.accountNumber(),
                IdempotencyFingerprint.normalizeAmount(request.amount()));

        Optional<TransferResponse> existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.accountNumber());
        }

        existing = replayIfPresent(request.idempotencyKey(), fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 1일 한도: 계좌 락 보유 상태에서 검증 (동시 요청이 잔여 한도를 나눠 우회하지 못함)
        transactionLimitService.validateDailyOutflow(account, request.amount());

        account.withdraw(request.amount());

        Transaction transaction = Transaction.builder()
                .fromAccount(account)
                .amount(request.amount())
                .type(Transaction.TransactionType.WITHDRAWAL)
                .description(request.description())
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        transaction.complete();

        transactionRepository.save(transaction);
        // 복식부기: 고객 DEBIT + 클리어링 CREDIT (외부 유출 자금의 상대 leg)
        ledgerService.recordWithdrawal(transaction, account, request.amount());
        return TransferResponse.from(transaction);
    }

    /**
     * 입금 처리 - 낙관적 락 사용.
     * 충돌 시 @Retryable이 재시도. 동시 입금이 많지만 충돌 확률이 낮을 때 적합.
     */
    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
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
            verifyFingerprint(existing.get(), idempotencyKey, fingerprint);
            metrics.countIdempotentReplay();
            return;
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

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
    }
}
