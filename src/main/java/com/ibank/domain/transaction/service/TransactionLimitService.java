package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionLimitExceededException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.config.TransactionLimitProperties;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 거래 한도 검증.
 *
 * <ul>
 *   <li>단건 한도: DB 접근이 필요 없으므로 락 획득 전에 fail-fast로 검사한다.</li>
 *   <li>1일 누적 출금 한도: 반드시 <b>출금 계좌의 비관적 락을 보유한 상태</b>에서 호출해야 한다.
 *       락이 계좌 단위로 출금을 직렬화하므로, 동시 요청이 잔여 한도를 나눠 쓰는 우회
 *       (각각은 한도 이내지만 합치면 초과)가 불가능하다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TransactionLimitProperties.class)
public class TransactionLimitService {

    private final TransactionLimitProperties properties;
    private final TransactionRepository transactionRepository;
    private final AccountHoldRepository holdRepository;
    private final BusinessClock businessClock;

    /** 단건(1회) 거래 한도. 이체/입금/출금 공통. */
    public void validateAmount(BigDecimal amount) {
        if (amount.compareTo(properties.perTransactionMax()) > 0) {
            throw new TransactionLimitExceededException(amount, properties.perTransactionMax());
        }
    }

    /**
     * 1일 누적 출금(이체+출금) 한도.
     *
     * <p>사용량 = <b>오늘 완료된 출금 합계 + 지금 묶여 있는 금액(ACTIVE 홀드 합계)</b>.
     *
     * <ul>
     *   <li>완료 출금은 요청 시각이 아니라 <b>완료 시각</b>으로 센다. 어제 보류됐다가 오늘 실행된 거래는
     *       오늘 빠져나간 돈이다. 요청 시각으로 세면 그 거래는 어느 날의 한도에도 잡히지 않는다.</li>
     *   <li>묶여 있는 금액도 사용량이다. 세지 않으면 한도 이내의 이체를 여러 건 보류시켜 둔 뒤
     *       한꺼번에 실행시켜 한도를 넘길 수 있다. 홀드가 취소·만료로 풀리면 ACTIVE가 아니게 되므로
     *       사용량에서 즉시 빠진다.</li>
     *   <li>"오늘"은 영업 타임존 기준이다.</li>
     * </ul>
     *
     * @param ownHold 이 거래 자신의 홀드. 보류된 거래를 실행할 때는 그 금액이 이미 사용량에 들어 있으므로
     *                빼고 계산한다. 새 거래를 가승인할 때는 null.
     */
    public void validateDailyOutflow(Account account, BigDecimal amount, AccountHold ownHold) {
        BusinessClock.DayRange today = businessClock.todayRange();
        BigDecimal completed = transactionRepository.sumCompletedOutflow(account.getId(), today.start(), today.end());
        BigDecimal held = holdRepository.sumActiveAmount(account.getId());
        if (ownHold != null && ownHold.isActive()) {
            held = held.subtract(ownHold.getAmount());
        }
        BigDecimal used = completed.add(held);
        if (used.add(amount).compareTo(properties.dailyOutflowMax()) > 0) {
            throw new DailyLimitExceededException(
                    account.getAccountNumber(), used, amount, properties.dailyOutflowMax());
        }
    }
}
