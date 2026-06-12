package com.ibank.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 단위 상관관계 ID(correlation ID) 필터.
 *
 * <p>클라이언트가 보낸 {@code X-Request-Id}를 이어받거나 없으면 생성해
 * MDC와 응답 헤더에 싣는다. 모든 로그 라인과 감사 로그가 같은 ID를 공유하므로
 * 하나의 요청을 로그·감사 기록·클라이언트 응답에 걸쳐 끝까지 추적할 수 있다.
 *
 * <p>외부 입력이 로그에 실리므로 형식을 검증한다(로그 인젝션·헤더 오염 방지).
 * 형식이 어긋나면 위조로 간주하지 않고 새로 발급한다.
 *
 * <p>보안 필터 체인보다 앞(서블릿 필터 최우선)에서 동작해 인증 실패·호출 제한 거부
 * 응답에도 ID가 실린다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    /** MDC 키. 로그 패턴의 {@code %X{requestId}}와 감사 로그가 함께 사용한다. */
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9\\-_.]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String sanitize(String value) {
        if (value == null || !VALID.matcher(value).matches()) {
            return null;
        }
        return value;
    }
}
