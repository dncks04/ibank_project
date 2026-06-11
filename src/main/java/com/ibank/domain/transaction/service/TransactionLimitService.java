package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionLimitExceededException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.config.TransactionLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    /** 단건(1회) 거래 한도. 이체/입금/출금 공통. */
    public void validateAmount(BigDecimal amount) {
        if (amount.compareTo(properties.perTransactionMax()) > 0) {
            throw new TransactionLimitExceededException(amount, properties.perTransactionMax());
        }
    }

    /**
     * 1일 누적 출금(이체+출금) 한도. 당일 완료된 출금 합계 + 이번 요청이 한도를 넘으면 거부.
     * 거부된 요청은 거래를 생성하지 않으므로 한도를 소모하지 않는다.
     */
    public void validateDailyOutflow(Account account, BigDecimal amount) {
        LocalDate today = LocalDate.now();
        BigDecimal used = transactionRepository.sumOutflowAmount(
                account.getId(),
                Transaction.TransactionStatus.COMPLETED,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay());
        if (used.add(amount).compareTo(properties.dailyOutflowMax()) > 0) {
            throw new DailyLimitExceededException(
                    account.getAccountNumber(), used, amount, properties.dailyOutflowMax());
        }
    }
}
