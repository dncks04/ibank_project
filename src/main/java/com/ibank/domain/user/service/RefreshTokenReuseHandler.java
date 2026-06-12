package com.ibank.domain.user.service;

import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.AuditService;
import com.ibank.global.security.AuthCacheEvictor;
import com.ibank.global.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰 재사용(탈취 의심) 대응.
 *
 * <p>회전(rotation)으로 이미 폐기된 토큰이 다시 제출되면 정상 사용자와 공격자를 구분할 수 없으므로,
 * 해당 사용자의 모든 리프레시 토큰을 무효화하여 양쪽 세션을 함께 끊고 보안 경보(감사 로그)를 남긴다.
 * 두 주체 모두 재인증을 강제받는다.
 *
 * <p><b>REQUIRES_NEW</b>: 호출 측({@code refresh})은 재사용을 401로 거부하며 트랜잭션을 롤백한다.
 * 무효화·감사 기록까지 함께 롤백되면 안 되므로, 이 처리는 독립 트랜잭션에서 커밋한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenReuseHandler {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuthCacheEvictor authCacheEvictor;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleReuse(User user) {
        int revokedCount = refreshTokenRepository.revokeAllByUserId(user.getId());
        userRepository.incrementTokenVersion(user.getId()); // 기존 access 토큰도 즉시 무효화
        authCacheEvictor.evictAfterCommit(); // 캐시된 옛 토큰 버전 제거 → 즉시 무효화
        log.warn("리프레시 토큰 재사용 탐지 — userId={}, 전체 세션 무효화(활성 토큰 {}개 폐기)",
                user.getId(), revokedCount);
        auditService.record(user.getLoginId(), "REFRESH_TOKEN_REUSE", String.valueOf(user.getId()),
                "FAILURE", "회전된 토큰 재사용 탐지로 전체 세션 무효화 (" + revokedCount + "개)", null,
                MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
