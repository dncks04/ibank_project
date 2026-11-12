package com.ibank.domain.timedeposit;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.service.AccountNumberGenerator;
import com.ibank.domain.hold.service.HoldService;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.outbox.DomainEvents;
import com.ibank.domain.outbox.EventType;
import com.ibank.domain.timedeposit.TimeDepositDtos.OpenRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminateRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminationResponse;
import com.ibank.domain.timedeposit.TimeDepositDtos.TimeDepositResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.IdempotencyFingerprint;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.Audited;
import com.ibank.global.config.TimeDepositProperties;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 정기예금 가입과 해지.
 *
 * <p>둘 다 고객 자신의 계좌 사이를 오가는 자금이라 FDS 판정을 거치지 않고 한 트랜잭션으로 끝낸다.
 * 대신 해지는 {@code TIME_DEPOSIT_TERMINATED} 이벤트를 남겨, FDS 서비스가 "예적금 해지 직후 전액 이체"
 * 같은 흐름을 뒤따르는 이체 판정에서 알아볼 수 있게 한다.
 *
 * <p>자금 이동은 모두 합이 0인 분개다.
 * <ul>
 *   <li>가입: 입출금 계좌 DEBIT + 정기예금 계좌 CREDIT</li>
 *   <li>해지(원금): 정기예금 계좌 DEBIT + 입출금 계좌 CREDIT</li>
 *   <li>해지(이자): 입출금 계좌 CREDIT + 은행 이자 비용 계정 DEBIT</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TimeDepositProperties.class)
public class TimeDepositService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final HoldService holdService;
    private final LedgerService ledgerService;
    private final DomainEvents events;
    private final BusinessClock businessClock;
    private final TimeDepositProperties properties;

    /**
     * 가입: 입출금 계좌의 돈을 새 정기예금 계좌로 옮긴다.
     * 같은 멱등성 키로 다시 요청하면 처음 만든 정기예금을 돌려준다.
     */
    @Audited(action = "TIME_DEPOSIT_OPEN", target = "#result.accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TimeDepositResponse open(Long userId, OpenRequest request) {
        Optional<Account> existing = accountRepository.findByOpenIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replayOpen(existing.get(), userId);
        }
        if (request.termMonths() < properties.minTermMonths() || request.termMonths() > properties.maxTermMonths()) {
            throw new IllegalArgumentException("가입 기간이 허용 범위를 벗어났습니다: " + request.termMonths());
        }

        Account funding = accountRepository.findByAccountNumberWithLock(request.fundingAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.fundingAccountNumber()));
        requireOwner(funding, userId);
        funding.ensureCanSend();
        // 보류 중인 거래가 묶어 둔 돈으로는 가입할 수 없다
        holdService.requireAvailable(funding, request.amount());

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        LocalDate maturityDate = businessClock.today().plusMonths(request.termMonths());
        // saveAndFlush: 같은 키의 동시 요청이면 UNIQUE 위반이 여기서 드러난다(409 → 재시도 시 위의 replay)
        Account timeDeposit = accountRepository.saveAndFlush(Account.timeDeposit(
                accountNumberGenerator.generate(), owner, maturityDate,
                properties.annualRate(), request.idempotencyKey()));

        funding.withdraw(request.amount());
        timeDeposit.deposit(request.amount());

        Transaction tx = Transaction.builder()
                .fromAccount(funding)
                .toAccount(timeDeposit)
                .amount(request.amount())
                .type(Transaction.TransactionType.TD_OPEN)
                .description("정기예금 가입")
                .build();
        tx.complete();
        transactionRepository.save(tx);
        ledgerService.recordTransfer(tx, funding, timeDeposit, request.amount());

        events.account(EventType.ACCOUNT_OPENED, timeDeposit, request.amount(), null);
        return TimeDepositResponse.from(timeDeposit);
    }

    /**
     * 해지: 원금과 이자를 입출금 계좌로 옮기고 정기예금 계좌를 닫는다.
     * 만기 전이면 중도해지 금리, 만기 이후면 약정 금리를 가입일부터 해지일까지 일할 계산한다.
     * 같은 멱등성 키로 다시 요청하면 처음 해지 결과를 돌려준다.
     */
    @Audited(action = "TIME_DEPOSIT_TERMINATE", target = "#accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TerminationResponse terminate(Long userId, String accountNumber, TerminateRequest request) {
        String fingerprint = IdempotencyFingerprint.of(
                Transaction.TransactionType.TD_TERMINATION.name(), accountNumber, request.payoutAccountNumber());

        // 데드락 방지: 두 계좌를 계좌번호 오름차순으로 잠근다 (이체와 같은 전역 순서)
        List<String> sorted = Stream.of(accountNumber, request.payoutAccountNumber()).distinct().sorted().toList();
        if (sorted.size() != 2) {
            throw new IllegalArgumentException("해지 대금을 받을 계좌는 정기예금 계좌와 달라야 합니다.");
        }
        Account first = lock(sorted.get(0));
        Account second = lock(sorted.get(1));
        Account timeDeposit = first.getAccountNumber().equals(accountNumber) ? first : second;
        Account payout = timeDeposit == first ? second : first;
        requireOwner(timeDeposit, userId);
        requireOwner(payout, userId);

        // 락을 쥔 뒤에 멱등성을 확인한다: 같은 키의 동시 요청은 앞선 쪽이 커밋한 해지 거래를 여기서 보게 된다
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            if (!fingerprint.equals(existing.get().getRequestFingerprint())) {
                throw new IdempotencyKeyConflictException(request.idempotencyKey());
            }
            return replayTermination(existing.get(), timeDeposit, payout);
        }

        if (!timeDeposit.isTimeDeposit()) {
            throw new IllegalArgumentException("정기예금 계좌가 아닙니다: " + accountNumber);
        }
        timeDeposit.ensureActive(); // 이미 해지된 정기예금이면 여기서 거부된다
        payout.ensureCanReceive();

        BigDecimal principal = timeDeposit.getBalance();
        LocalDate today = businessClock.today();
        boolean early = today.isBefore(timeDeposit.getMaturityDate());
        BigDecimal rate = early ? properties.earlyTerminationRate() : timeDeposit.getContractRate();
        BigDecimal interest = interestOf(principal, rate, openedOn(timeDeposit), today);

        // 원금: 정기예금 계좌 → 입출금 계좌
        timeDeposit.withdraw(principal);
        payout.deposit(principal);
        Transaction tx = Transaction.builder()
                .fromAccount(timeDeposit)
                .toAccount(payout)
                .amount(principal)
                .type(Transaction.TransactionType.TD_TERMINATION)
                .description(early ? "정기예금 중도해지" : "정기예금 만기해지")
                .build()
                .withIdempotency(request.idempotencyKey(), fingerprint);
        tx.complete();
        transactionRepository.save(tx);
        ledgerService.recordTransfer(tx, timeDeposit, payout, principal);

        // 이자: 은행 이자 비용 계정 → 입출금 계좌
        if (interest.signum() > 0) {
            payout.deposit(interest);
            ledgerService.recordInterestPayment(payout, interest);
        }

        timeDeposit.recordPaidInterest(interest);
        timeDeposit.close(request.idempotencyKey());

        events.timeDepositTerminated(timeDeposit, payout, tx, principal, interest, early);
        return new TerminationResponse(timeDeposit.getAccountNumber(), payout.getAccountNumber(),
                principal, interest, early, rate, tx.getId());
    }

    // ---- 헬퍼 ----

    private TimeDepositResponse replayOpen(Account existing, Long userId) {
        requireOwner(existing, userId);
        if (!existing.isTimeDeposit()) {
            // 같은 키가 일반 계좌 개설에 이미 쓰였다
            throw new IdempotencyKeyConflictException(existing.getOpenIdempotencyKey());
        }
        return TimeDepositResponse.from(existing);
    }

    private TerminationResponse replayTermination(Transaction tx, Account timeDeposit, Account payout) {
        boolean early = tx.getCompletedAt() != null
                && businessClock.toBusinessOffset(tx.getCompletedAt()).toLocalDate().isBefore(timeDeposit.getMaturityDate());
        return new TerminationResponse(timeDeposit.getAccountNumber(), payout.getAccountNumber(),
                tx.getAmount(), timeDeposit.getPaidInterest(), early,
                early ? properties.earlyTerminationRate() : timeDeposit.getContractRate(), tx.getId());
    }

    /** 일할 이자 = 원금 × 연이율 × 경과일수 / 기준일수. 은행 관행대로 HALF_EVEN, 원 단위 소수 둘째 자리. */
    private BigDecimal interestOf(BigDecimal principal, BigDecimal annualRate, LocalDate from, LocalDate to) {
        long days = Math.max(0, ChronoUnit.DAYS.between(from, to));
        return principal.multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(properties.dayCountBasis()), 2, RoundingMode.HALF_EVEN);
    }

    /** 가입일(영업 타임존 기준 날짜). */
    private LocalDate openedOn(Account timeDeposit) {
        return businessClock.toBusinessOffset(timeDeposit.getCreatedAt()).toLocalDate();
    }

    private Account lock(String accountNumber) {
        return accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    private void requireOwner(Account account, Long userId) {
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(account.getAccountNumber());
        }
    }
}
