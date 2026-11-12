package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.hold.service.HoldService;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.Channel;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.entity.Transaction.TransactionType;
import com.ibank.domain.transaction.entity.TransactionDecision;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.HeldTransactionNotFoundException;
import com.ibank.domain.transaction.exception.TransactionLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionNotHeldException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.metrics.IbankMetrics;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 2단계 거래 처리의 트랜잭션 단계들. 이체와 출금이 같은 흐름을 탄다.
 *
 * <pre>
 * [1단계 reserve*]   짧은 트랜잭션, 계좌 락 보유
 *                    멱등성·소유권·계좌 상태·1일 한도·가용잔액 검증 → 거래 PENDING_REVIEW + 홀드 ACTIVE → 커밋
 * [2단계]            락·트랜잭션 없이 판정 호출 — 이 클래스 밖({@link TransferService})에서 수행
 * [3단계 applyDecision]
 *                    APPROVE → 락 재획득 → 실행 시점 재검증 → 홀드 CONSUMED → 자금 이동·분개 → COMPLETED
 *                    HOLD    → HELD (홀드는 ACTIVE 유지)
 * [보류 거래 처리]    executeHeld: HELD → COMPLETED / cancelHeld: HELD → CANCELLED
 * </pre>
 *
 * <p><b>락 순서</b>: 거래가 이미 있는 경로(3단계, 실행, 취소)는 항상 <b>거래 행 → 계좌(번호 오름차순)</b>
 * 순으로 잠근다. 1단계는 계좌를 잠근 뒤 거래 행을 새로 insert하므로 거래 행 락을 기다리는 일이 없어
 * 순환 대기가 생기지 않는다. 같은 거래에 대한 실행·취소·확정은 거래 행 락에서 직렬화되어 하나만 적용된다.
 *
 * <p><b>거래 행은 반드시 락 쿼리로 처음 읽는다.</b> 같은 영속성 컨텍스트에서 락 없이 먼저 읽은 엔티티가 있으면
 * 락 쿼리는 그 인스턴스를 그대로 돌려주므로 옛 상태를 보고 판단하게 된다. 계좌도 같은 이유로 엔티티를 먼저
 * 로딩하지 않고 번호만 읽은 뒤 잠근다({@link AccountRepository#findAccountNumberById}).
 */
@Service
@RequiredArgsConstructor
public class TransactionProcessor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldRepository holdRepository;
    private final HoldService holdService;
    private final LedgerService ledgerService;
    private final TransactionLimitService limitService;
    private final IdempotencyGuard idempotencyGuard;
    private final BusinessClock businessClock;
    private final IbankMetrics metrics;
    // 이관 전 임시: 기존 룰 기반 탐지가 켜져 있으면 보류 시 관리자 검토용 경보를 남긴다. FDS 제거 단계에서 삭제한다.
    private final FraudAlertRepository fraudAlertRepository;
    @Value("${ibank.fraud.enabled:false}")
    private boolean legacyFraudAlerts;

    // ------------------------------------------------------------------
    // 1단계: 가승인
    // ------------------------------------------------------------------

    /** 이체 가승인. 같은 멱등성 키의 거래가 이미 있으면 그 상태를 그대로 돌려준다(replay). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation reserveTransfer(Long userId, TransferRequest request, String fingerprint, Channel channel) {
        // 1차 멱등성 검증 (락 없음): 명백한 중복 요청 빠른 반환
        Optional<Transaction> existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return Reservation.replay(TransferResponse.from(existing.get()));
        }

        LockedPair accounts = lockInOrder(request.fromAccountNumber(), request.toAccountNumber());

        // 2차 멱등성 검증 (락 보유): 같은 키의 동시 요청이 락 대기 후 들어온 경우.
        // READ_COMMITTED이므로 락 대기 중 상대가 커밋한 거래가 이 시점에 보인다.
        existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return Reservation.replay(TransferResponse.from(existing.get()));
        }

        Account from = accounts.from();
        Account to = accounts.to();
        requireOwner(from, userId, request.fromAccountNumber());
        from.ensureActive();
        to.ensureActive();
        // 1일 한도: 오늘 완료된 출금 + 이미 묶여 있는 금액 + 이번 요청
        limitService.validateDailyOutflow(from, request.amount(), null);

        Transaction tx = Transaction.builder()
                .fromAccount(from)
                .toAccount(to)
                .amount(request.amount())
                .type(TransactionType.TRANSFER)
                .description(request.description())
                .channel(channel)
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        return reserve(tx, from, request.fromAccountNumber(), request.toAccountNumber());
    }

    /** 출금 가승인. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Reservation reserveWithdrawal(Long userId, WithdrawRequest request, String fingerprint, Channel channel) {
        Optional<Transaction> existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return Reservation.replay(TransferResponse.from(existing.get()));
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        existing = idempotencyGuard.findReplay(request.idempotencyKey(), fingerprint, userId);
        if (existing.isPresent()) {
            return Reservation.replay(TransferResponse.from(existing.get()));
        }

        requireOwner(account, userId, request.accountNumber());
        account.ensureActive();
        limitService.validateDailyOutflow(account, request.amount(), null);

        Transaction tx = Transaction.builder()
                .fromAccount(account)
                .amount(request.amount())
                .type(TransactionType.WITHDRAWAL)
                .description(request.description())
                .channel(channel)
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        return reserve(tx, account, request.accountNumber(), null);
    }

    /** 거래를 PENDING_REVIEW로 저장하고 금액을 묶는다. 가용잔액이 부족하면 여기서 실패해 전체가 롤백된다. */
    private Reservation reserve(Transaction tx, Account lockedFrom, String fromNumber, String toNumber) {
        tx.submitForReview();
        transactionRepository.save(tx);
        holdService.place(lockedFrom, tx, tx.getAmount());

        return Reservation.reserved(tx.getId(), new EvaluationRequest(
                tx.getId(),
                tx.getIdempotencyKey(),
                tx.getType(),
                tx.getChannel(),
                fromNumber,
                toNumber,
                tx.getAmount(),
                lockedFrom.getOwner().getId(),
                businessClock.toBusinessOffset(tx.getCreatedAt())));
    }

    /**
     * 판정 대기 중인 거래의 판정 요청을 다시 만든다. 판정 도중 처리가 끊긴 거래를 복구 배치가 재판정할 때 쓴다.
     * 거래가 이미 PENDING_REVIEW가 아니면(그사이 다른 경로가 처리함) 빈 값.
     */
    @Transactional(readOnly = true)
    public Optional<EvaluationRequest> evaluationRequestOf(Long transactionId) {
        return transactionRepository.findById(transactionId)
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING_REVIEW)
                .map(tx -> new EvaluationRequest(
                        tx.getId(),
                        tx.getIdempotencyKey(),
                        tx.getType(),
                        tx.getChannel(),
                        tx.getFromAccount().getAccountNumber(),
                        tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                        tx.getAmount(),
                        tx.getFromAccount().getOwner().getId(),
                        businessClock.toBusinessOffset(tx.getCreatedAt())));
    }

    // ------------------------------------------------------------------
    // 3단계: 판정 반영
    // ------------------------------------------------------------------

    /**
     * 판정을 거래에 반영한다.
     *
     * <p>거래가 이미 PENDING_REVIEW가 아니면(복구 배치나 다른 경로가 먼저 처리함) 아무것도 바꾸지 않고
     * 현재 상태를 돌려준다. 따라서 같은 거래에 여러 번 호출해도 안전하다.
     *
     * <p>APPROVE인데 실행 시점 재검증에 실패하면 거래를 FAILED로, 홀드를 RELEASED로 <b>커밋한 뒤</b>
     * 그 사유를 {@link Outcome#failure()}에 담아 돌려준다. 예외를 던지면 롤백되어 거래가 PENDING_REVIEW로
     * 남고 금액이 계속 묶이기 때문이다. 호출자가 커밋 뒤에 그 예외를 던진다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome applyDecision(Long transactionId, Decision decision) {
        Transaction tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new HeldTransactionNotFoundException(transactionId));
        if (tx.getStatus() != TransactionStatus.PENDING_REVIEW) {
            return Outcome.of(TransferResponse.from(tx));
        }
        tx.recordDecision(TransactionDecision.from(decision, businessClock.nowForStorage()));

        if (decision.isHold()) {
            tx.hold();
            recordLegacyAlert(tx, decision);
            metrics.countOperation(tx.getType().name() + "_HELD", "SUCCESS");
            return Outcome.of(TransferResponse.from(tx));
        }

        Settlement settlement = prepareSettlement(tx);
        try {
            settlement.revalidate();
        } catch (InactiveAccountException | DailyLimitExceededException
                 | TransactionLimitExceededException | InsufficientBalanceException e) {
            // 가승인과 확정 사이에 계좌가 해지·정지되는 등 조건이 바뀌었다. 묶은 금액을 풀고 실패로 확정한다.
            tx.fail(failureCodeOf(e));
            holdService.release(settlement.hold());
            metrics.countOperation(tx.getType().name() + "_FAILED", "FAILURE");
            return Outcome.failed(TransferResponse.from(tx), e);
        }
        settlement.execute();
        return Outcome.of(TransferResponse.from(tx));
    }

    // ------------------------------------------------------------------
    // 보류 거래 처리
    // ------------------------------------------------------------------

    /**
     * 보류 거래 실행: HELD → 실행 시점 재검증 → COMPLETED.
     * 재검증에 실패하면 예외가 전파되어 롤백되고, 거래는 HELD로 남는다(홀드도 ACTIVE 유지).
     *
     * @throws TransactionNotHeldException 거래가 HELD가 아님(이미 실행·취소·만료됨)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse executeHeld(Long transactionId) {
        Transaction tx = lockHeld(transactionId);
        Settlement settlement = prepareSettlement(tx);
        settlement.revalidate();
        settlement.execute();
        metrics.countOperation("HELD_EXECUTE", "SUCCESS");
        return TransferResponse.from(tx);
    }

    /**
     * 보류 거래 취소: HELD → CANCELLED, 홀드 RELEASED. 원장 분개를 만들지 않는다.
     *
     * @throws TransactionNotHeldException 거래가 HELD가 아님
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse cancelHeld(Long transactionId) {
        Transaction tx = lockHeld(transactionId);
        AccountHold hold = holdRepository.findByTransactionId(tx.getId()).orElseThrow();
        // 홀드 해제는 계좌 락 안에서: 동시에 진행 중인 출금의 가용잔액 계산과 직렬화한다
        lockAccount(tx.getFromAccount().getId());
        holdService.release(hold);
        tx.cancel();
        metrics.countOperation("HELD_CANCEL", "SUCCESS");
        return TransferResponse.from(tx);
    }

    private Transaction lockHeld(Long transactionId) {
        Transaction tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new HeldTransactionNotFoundException(transactionId));
        if (tx.getStatus() != TransactionStatus.HELD) {
            throw new TransactionNotHeldException(transactionId, tx.getStatus());
        }
        return tx;
    }

    // ------------------------------------------------------------------
    // 실행(자금 이동) 공통
    // ------------------------------------------------------------------

    /** 거래 행 락을 쥔 상태에서 호출한다. 홀드를 읽고 계좌를 번호 오름차순으로 잠근다. */
    private Settlement prepareSettlement(Transaction tx) {
        AccountHold hold = holdRepository.findByTransactionId(tx.getId()).orElseThrow();
        if (tx.getToAccount() == null) {
            return new Settlement(tx, hold, lockAccount(tx.getFromAccount().getId()), null);
        }
        String fromNumber = accountNumberOf(tx.getFromAccount().getId());
        String toNumber = accountNumberOf(tx.getToAccount().getId());
        LockedPair accounts = lockInOrder(fromNumber, toNumber);
        return new Settlement(tx, hold, accounts.from(), accounts.to());
    }

    /** 잠긴 계좌와 홀드를 들고 재검증·실행을 수행한다. */
    private final class Settlement {
        private final Transaction tx;
        private final AccountHold hold;
        private final Account from;
        private final Account to;

        private Settlement(Transaction tx, AccountHold hold, Account from, Account to) {
            this.tx = tx;
            this.hold = hold;
            this.from = from;
            this.to = to;
        }

        AccountHold hold() {
            return hold;
        }

        /**
         * 실행 시점 기준 재검증. 아무것도 바꾸지 않으므로 실패해도 되돌릴 것이 없다.
         * 순서: 계좌 상태 → 단건 한도 → 1일 한도 → 가용잔액.
         */
        void revalidate() {
            BigDecimal amount = tx.getAmount();
            from.ensureActive();
            if (to != null) {
                to.ensureActive();
            }
            limitService.validateAmount(amount);
            // 이 거래의 홀드는 이미 한도 사용량에 들어 있으므로 빼고 계산한다
            limitService.validateDailyOutflow(from, amount, hold);
            // 가용잔액: 이 거래가 묶어 둔 금액은 이 거래가 쓸 수 있다
            BigDecimal usable = holdService.availableBalance(from).add(hold.getAmount());
            if (usable.compareTo(amount) < 0) {
                throw new InsufficientBalanceException(from.getAccountNumber(), usable, amount);
            }
        }

        /** 홀드를 실제 출금으로 전환하고 자금을 옮긴다. 분개는 합이 0이다. */
        void execute() {
            BigDecimal amount = tx.getAmount();
            holdService.consume(hold);
            from.withdraw(amount);
            if (to != null) {
                to.deposit(amount);
            }
            tx.complete();
            if (to != null) {
                // 출금 계좌 DEBIT + 입금 계좌 CREDIT
                ledgerService.recordTransfer(tx, from, to, amount);
            } else {
                // 고객 DEBIT + 클리어링 CREDIT (외부 유출 자금의 상대 leg)
                ledgerService.recordWithdrawal(tx, from, amount);
            }
        }
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    /** 데드락 방지: 두 계좌를 항상 계좌번호 오름차순으로 잠근다 (전역 락 순서 고정). */
    private LockedPair lockInOrder(String fromNumber, String toNumber) {
        List<String> sorted = Stream.of(fromNumber, toNumber).sorted().toList();
        Account first = accountRepository.findByAccountNumberWithLock(sorted.get(0))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sorted.get(1))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(1)));
        return first.getAccountNumber().equals(fromNumber)
                ? new LockedPair(first, second)
                : new LockedPair(second, first);
    }

    private Account lockAccount(Long accountId) {
        String number = accountNumberOf(accountId);
        return accountRepository.findByAccountNumberWithLock(number)
                .orElseThrow(() -> new AccountNotFoundException(number));
    }

    /** 엔티티를 로딩하지 않고 번호만 읽는다 — 락 전에 엔티티를 올리면 락 쿼리와 버전이 충돌할 수 있다. */
    private String accountNumberOf(Long accountId) {
        return accountRepository.findAccountNumberById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(String.valueOf(accountId)));
    }

    private void requireOwner(Account account, Long userId, String accountNumber) {
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(accountNumber);
        }
    }

    private static String failureCodeOf(RuntimeException e) {
        if (e instanceof InactiveAccountException) {
            return "ACCOUNT_NOT_ACTIVE";
        }
        if (e instanceof DailyLimitExceededException) {
            return "DAILY_LIMIT_EXCEEDED";
        }
        if (e instanceof TransactionLimitExceededException) {
            return "PER_TRANSACTION_LIMIT_EXCEEDED";
        }
        return "INSUFFICIENT_AVAILABLE_BALANCE";
    }

    private void recordLegacyAlert(Transaction tx, Decision decision) {
        if (legacyFraudAlerts && !decision.reasonCodes().isEmpty()) {
            fraudAlertRepository.save(FraudAlert.of(
                    tx.getFromAccount().getId(), tx.getId(), decision.reasonCodes(), tx.getAmount(),
                    "이상거래 탐지로 이체 보류: " + String.join(",", decision.reasonCodes())));
        }
    }

    private record LockedPair(Account from, Account to) {}

    /**
     * 1단계 결과. replay면 기존 거래의 응답이, 새로 가승인했으면 판정 요청이 들어 있다.
     */
    public record Reservation(Long transactionId, TransferResponse replay, EvaluationRequest evaluation) {

        static Reservation replay(TransferResponse existing) {
            return new Reservation(existing.transactionId(), existing, null);
        }

        static Reservation reserved(Long transactionId, EvaluationRequest evaluation) {
            return new Reservation(transactionId, null, evaluation);
        }

        public boolean isReplay() {
            return replay != null;
        }
    }

    /**
     * 3단계 결과. {@code failure}가 있으면 거래는 FAILED로 커밋됐고, 호출자가 이 예외를 클라이언트에 던진다.
     */
    public record Outcome(TransferResponse response, RuntimeException failure) {

        static Outcome of(TransferResponse response) {
            return new Outcome(response, null);
        }

        static Outcome failed(TransferResponse response, RuntimeException failure) {
            return new Outcome(response, failure);
        }
    }
}
