package com.ibank.internal.auth;

import com.ibank.global.audit.AuditService;
import com.ibank.global.config.InternalApiProperties;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.security.JwtProvider;
import com.ibank.global.security.LoginAttemptService;
import com.ibank.global.web.ClientIpResolver;
import com.ibank.global.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 시스템 토큰 발급. 다른 서비스가 설정에 등록된 클라이언트 ID·시크릿으로 {@code ROLE_SYSTEM} 토큰을 받는다.
 *
 * <p>이 엔드포인트만 {@code /internal/**} 중 인증 없이 열려 있다. 그래서 시크릿 대입 시도를 로그인과 같은 방식으로
 * 막는다: 클라이언트 ID와 출발지 IP 단위로 실패를 세어 임계치를 넘으면 일정 시간 잠근다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalAuthController {

    private static final String ATTEMPT_KEY_PREFIX = "system-client:";

    private final InternalApiProperties properties;
    private final JwtProvider jwtProvider;
    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver clientIpResolver;
    private final AuditService auditService;

    public record TokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}

    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>> issue(@Valid @RequestBody TokenRequest request,
                                                            HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        String attemptKey = ATTEMPT_KEY_PREFIX + request.clientId();

        if (loginAttemptService.isLocked(attemptKey) || loginAttemptService.isIpLocked(ip)) {
            return unauthorized(request.clientId(), ip, "LOCKED");
        }
        if (!secretMatches(request)) {
            loginAttemptService.recordFailure(attemptKey);
            loginAttemptService.recordIpFailure(ip);
            return unauthorized(request.clientId(), ip, "BAD_CREDENTIALS");
        }
        loginAttemptService.reset(attemptKey);

        String token = jwtProvider.generateSystemToken(request.clientId(), properties.tokenTtl());
        audit(request.clientId(), "SUCCESS", null, ip);
        return ResponseEntity.ok(ApiResponse.success(
                new TokenResponse(token, "Bearer", properties.tokenTtl().toSeconds())));
    }

    /**
     * 등록되지 않은 클라이언트도 더미 값과 비교해, 응답 시간으로 클라이언트 ID의 존재 여부를 알 수 없게 한다.
     * 비교는 상수 시간({@link MessageDigest#isEqual})으로 한다.
     */
    private boolean secretMatches(TokenRequest request) {
        String expected = properties.clients().get(request.clientId());
        byte[] expectedBytes = (expected != null ? expected : "\u0000unregistered-client").getBytes(StandardCharsets.UTF_8);
        boolean equal = MessageDigest.isEqual(expectedBytes, request.clientSecret().getBytes(StandardCharsets.UTF_8));
        return expected != null && !expected.isBlank() && equal;
    }

    private ResponseEntity<ApiResponse<TokenResponse>> unauthorized(String clientId, String ip, String reason) {
        audit(clientId, "FAILURE", reason, ip);
        // 클라이언트가 없는지, 시크릿이 틀렸는지, 잠겼는지 구분해 알려주지 않는다
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("클라이언트 인증에 실패했습니다.", "UNAUTHORIZED"));
    }

    private void audit(String clientId, String result, String detail, String ip) {
        try {
            String actor = clientId.length() > 50 ? clientId.substring(0, 50) : clientId;
            auditService.record(actor, "SYSTEM_TOKEN_ISSUE", null, result, detail, ip,
                    MDC.get(CorrelationIdFilter.MDC_KEY));
        } catch (Exception e) {
            log.error("시스템 토큰 발급 감사 기록 실패 — 발급 흐름은 유지", e);
        }
    }
}
