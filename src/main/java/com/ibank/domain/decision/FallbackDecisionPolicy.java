package com.ibank.domain.decision;

import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.config.FdsProperties;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FDS 서비스에서 판정을 받지 못했을 때 ibank가 대신 내리는 판정.
 *
 * <ul>
 *   <li>고객의 만 나이가 기준({@code ibank.fds.fallback.elder-age}, 기본 65세) 이상 → 보류</li>
 *   <li>나이를 알 수 없음(생년월일 없는 기존 고객, 고객 조회 실패) → 보류</li>
 *   <li>그 외 → 승인</li>
 * </ul>
 *
 * <p>모든 거래를 막으면 FDS 장애가 곧 뱅킹 장애가 되고, 모두 통과시키면 보호 대상 고객이 가장 위험한 순간에
 * 무방비가 된다. 그래서 보호 대상(고령)과 판단 근거가 없는 경우만 보류 쪽으로 기운다.
 * 보류된 거래는 금액이 묶인 채 남으므로, FDS 서비스가 복구된 뒤 이벤트를 보고 실행·취소를 결정할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class FallbackDecisionPolicy {

    /** 대체 정책이 보류할 때 붙이는 사유 코드. 화면과 FDS 서비스가 "판정 서비스 장애로 인한 보류"임을 구분한다. */
    public static final String REASON_FDS_UNAVAILABLE = "FDS_UNAVAILABLE";

    private final UserRepository userRepository;
    private final BusinessClock businessClock;
    private final FdsProperties properties;

    public Decision decide(EvaluationRequest request) {
        Integer age = userRepository.findById(request.customerId())
                .map(User::getBirthDate)
                .map(businessClock::ageOf)
                .orElse(null);

        boolean protect = age == null || age >= properties.fallback().elderAge();
        return protect
                ? Decision.hold(Source.FALLBACK, List.of(REASON_FDS_UNAVAILABLE))
                : Decision.approve(Source.FALLBACK);
    }
}
