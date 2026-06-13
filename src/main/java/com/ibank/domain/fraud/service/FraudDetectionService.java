package com.ibank.domain.fraud.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.config.FraudDetectionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 룰 기반 이상거래 탐지.
 *
 * <p>거래 한도와 마찬가지로 <b>출금 계좌의 비관적 락을 보유한 상태</b>에서 호출해야 한다.
 * velocity 룰이 최근 거래 수를 세는데, 락이 계좌 단위 출금을 직렬화하므로 동시 요청이
 * 카운트를 회피하는 레이스(TOCTOU)가 생기지 않는다.
 *
 * <p>탐지는 차단이 아니라 <b>보류</b>를 위한 신호다. 발동한 룰 목록을 반환하고,
 * 호출자(이체 서비스)가 보류 처리와 경보 기록을 담당한다. 비활성 시 항상 빈 목록.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(FraudDetectionProperties.class)
public class FraudDetectionService {

    public static final String RULE_VELOCITY = "VELOCITY";
    public static final String RULE_NIGHT_HIGH_VALUE = "NIGHT_HIGH_VALUE";
    public static final String RULE_NEW_PAYEE_HIGH_VALUE = "NEW_PAYEE_HIGH_VALUE";

    private final FraudDetectionProperties props;
    private final TransactionRepository transactionRepository;

    /**
     * 이체를 평가해 발동한 룰 목록을 반환한다. 빈 목록이면 정상 진행.
     *
     * @param now 평가 기준 시각(테스트 주입 가능)
     */
    public List<String> evaluate(Account from, Account to, BigDecimal amount, LocalDateTime now) {
        List<String> triggered = new ArrayList<>();
        if (!props.enabled()) {
            return triggered;
        }

        // 1) velocity: 짧은 창 안에서 출금성 거래가 과도하게 몰리는 경우
        long recentCount = transactionRepository.countOutgoingSince(
                from.getId(), now.minusMinutes(props.velocityWindowMinutes()));
        if (recentCount >= props.velocityMaxOutgoing()) {
            triggered.add(RULE_VELOCITY);
        }

        // 2) 심야 고액: 심야 시간대의 고액 이체
        int hour = now.getHour();
        boolean night = hour >= props.nightStartHour() && hour < props.nightEndHour();
        if (night && amount.compareTo(props.nightHighValueMin()) >= 0) {
            triggered.add(RULE_NIGHT_HIGH_VALUE);
        }

        // 3) 신규 수취인 고액: 거래 이력이 없는 계좌로의 고액 이체
        if (amount.compareTo(props.newPayeeHighValueMin()) >= 0
                && !transactionRepository.hasTransferTo(from.getId(), to.getId(),
                        Transaction.TransactionStatus.COMPLETED)) {
            triggered.add(RULE_NEW_PAYEE_HIGH_VALUE);
        }

        return triggered;
    }
}
