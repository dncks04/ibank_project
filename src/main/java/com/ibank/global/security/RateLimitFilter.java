package com.ibank.global.security;

import com.ibank.global.config.RateLimitProperties;
import com.ibank.global.metrics.IbankMetrics;
import com.ibank.global.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 출발지 IP 단위 호출 제한 필터. {@code /api/**} 요청에만 적용한다.
 *
 * <p>인증 이전(JWT 필터 앞)에서 동작해 과도한 요청을 인증·DB 작업 전에 싸게 차단한다.
 * 초과 시 429와 {@code Retry-After}를 반환한다. 비활성 시에는 동작하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties props;
    private final ClientIpResolver clientIpResolver;
    private final IbankMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 비활성이거나 /api 이외 경로(actuator·정적 등)는 제한 대상이 아니다.
        return !props.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);
        RateLimiter.Probe probe = rateLimiter.tryConsume(ip);
        if (!probe.allowed()) {
            metrics.countRateLimitRejection();
            log.warn("호출 제한 초과: ip={} {} {}", ip, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(probe.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"success\":false,\"data\":null,\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
