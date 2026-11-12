package com.ibank.domain.hold.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.global.config.HoldProperties;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 홀드와 가용잔액.
 *
 * <p><b>호출 규약</b>: 금액을 바꾸는 메서드({@link #place}, {@link #consume}, {@link #release}, {@link #expire})와
 * 가용잔액 검증({@link #requireAvailable})은 반드시 <b>해당 계좌의 비관적 락을 쥔 트랜잭션 안에서</b> 호출한다.
 * 락이 계좌 단위로 출금·홀드를 직렬화하므로, 동시 요청이 각자 가용잔액을 읽고 합쳐서 초과하는 일이 없다.
 * 트랜잭션 없이 호출하면 {@code MANDATORY} 전파가 예외를 던진다. 락 보유 여부까지는 여기서 확인할 수 없으므로
 * 호출자가 {@code findByAccountNumberWithLock}으로 읽은 계좌를 넘겨야 한다.
 *
 * <p>홀드는 원장 분개를 만들지 않는다. 원장잔액은 그대로이고 가용잔액만 변한다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(HoldProperties.class)
public class HoldService {

    private final AccountHoldRepository holdRepository;
    private final HoldProperties properties;
    private final BusinessClock businessClock;

    /** 계좌의 ACTIVE 홀드 합계. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal heldAmount(Long accountId) {
        return holdRepository.sumActiveAmount(accountId);
    }

    /** 가용잔액 = 원장잔액 − ACTIVE 홀드 합계. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal availableBalance(Account account) {
        return account.getBalance().subtract(heldAmount(account.getId()));
    }

    /**
     * 가용잔액이 금액 이상인지 검증한다. 부족하면 {@link InsufficientBalanceException}.
     * 홀드 없이 바로 출금하는 경로에서, 묶여 있는 금액을 빼 가지 못하게 막는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAvailable(Account lockedAccount, BigDecimal amount) {
        BigDecimal available = availableBalance(lockedAccount);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(lockedAccount.getAccountNumber(), available, amount);
        }
    }

    /**
     * 거래 금액을 계좌에 묶는다. 가용잔액이 부족하면 {@link InsufficientBalanceException}.
     * 만료 시각은 지금부터 최대 보류 시간({@code ibank.holds.max-duration}) 뒤다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountHold place(Account lockedAccount, Transaction transaction, BigDecimal amount) {
        requireAvailable(lockedAccount, amount);
        LocalDateTime now = businessClock.nowForStorage();
        return holdRepository.save(
                AccountHold.active(lockedAccount, transaction, amount, now, now.plus(properties.maxDuration())));
    }

    /** 거래 실행: 홀드를 실제 출금으로 전환한다. 잔액 차감은 호출자가 같은 트랜잭션에서 수행한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(AccountHold hold) {
        hold.consume(businessClock.nowForStorage());
    }

    /** 거래 취소·실패: 묶인 금액을 푼다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(AccountHold hold) {
        hold.release(businessClock.nowForStorage());
    }

    /** 최대 보류 시간 경과: 묶인 금액을 푼다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(AccountHold hold) {
        hold.expire(businessClock.nowForStorage());
    }
}
