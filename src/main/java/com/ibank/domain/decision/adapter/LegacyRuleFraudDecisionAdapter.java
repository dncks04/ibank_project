package com.ibank.domain.decision.adapter;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.decision.FraudDecisionPort;
import com.ibank.domain.fraud.service.FraudDetectionService;
import com.ibank.domain.transaction.entity.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <b>이관 전 임시 어댑터.</b> ibank에 남아 있는 기존 룰 기반 탐지({@link FraudDetectionService})를
 * 새 판정 포트 뒤에 끼워, 2단계 처리 구조에서도 기존 동작(룰에 걸리면 보류 → 관리자 검토)이 유지되게 한다.
 * 탐지 책임이 FDS 서비스로 넘어가는 단계에서 {@code FraudDetectionService}와 함께 삭제한다.
 *
 * <p>{@code ibank.fraud.enabled=true}일 때만 등록되고, 그때는 다른 어댑터보다 우선한다.
 *
 * <p>기존과 달라진 점: 예전에는 출금 계좌 락 안에서 평가했지만 이제 락 밖에서 평가한다(판정 호출 중에는
 * 락을 쥐지 않는다는 원칙). 그래서 VELOCITY 건수에는 판정 중인 거래 자신(PENDING_REVIEW)이 포함된다.
 * 기존 구현처럼 이체만 평가하고 출금은 그대로 승인한다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "ibank.fraud.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LegacyRuleFraudDecisionAdapter implements FraudDecisionPort {

    private final FraudDetectionService fraudDetectionService;
    private final AccountRepository accountRepository;

    @Override
    public Decision evaluate(EvaluationRequest request) {
        if (request.type() != Transaction.TransactionType.TRANSFER) {
            return Decision.approve(Source.LOCAL);
        }
        Account from = accountRepository.findByAccountNumber(request.fromAccountNumber()).orElseThrow();
        Account to = accountRepository.findByAccountNumber(request.toAccountNumber()).orElseThrow();

        List<String> rules = fraudDetectionService.evaluate(from, to, request.amount(), LocalDateTime.now());
        return rules.isEmpty() ? Decision.approve(Source.LOCAL) : Decision.hold(Source.LOCAL, rules);
    }
}
