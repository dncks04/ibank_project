package com.ibank.global.audit;

import com.ibank.global.web.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * {@link Audited} 메서드를 감싸 감사 로그를 남긴다.
 *
 * HIGHEST_PRECEDENCE: 트랜잭션 어드바이스보다 바깥에서 동작 → 비즈니스 트랜잭션이
 * 롤백되어도 실패 감사 기록이 남는다(감사는 REQUIRES_NEW 별도 트랜잭션).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";
    /** audit_logs.target 컬럼 길이. */
    private static final int TARGET_MAX_LENGTH = 100;

    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(com.ibank.global.audit.Audited)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Audited audited = signature.getMethod().getAnnotation(Audited.class);

        String actor = currentActor();
        String ip = currentIp();

        try {
            Object result = joinPoint.proceed();
            String target = resolveTarget(audited, joinPoint, signature.getMethod(), result);
            auditService.record(actor, audited.action(), target, SUCCESS, null, ip);
            return result;
        } catch (Throwable t) {
            String target = resolveTarget(audited, joinPoint, signature.getMethod(), null);
            auditService.record(actor, audited.action(), target, FAILURE, t.getClass().getSimpleName(), ip);
            throw t;
        }
    }

    /**
     * 감사 대상(target)을 결정한다.
     * {@code @Audited(target=...)} SpEL 표현식이 있으면 메서드 인자/{@code #result}에 대해 평가하고,
     * 없으면 첫 String 인자를 사용한다. 표현식 평가 실패는 감사 자체를 막지 않는다(best-effort).
     */
    private String resolveTarget(Audited audited, ProceedingJoinPoint joinPoint, Method method, Object result) {
        String expression = audited.target();
        if (expression == null || expression.isBlank()) {
            return truncate(firstStringArg(joinPoint.getArgs()));
        }
        try {
            EvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
            context.setVariable("result", result);
            Expression parsed = expressionParser.parseExpression(expression);
            Object value = parsed.getValue(context);
            return value != null ? truncate(value.toString()) : null;
        } catch (Exception e) {
            // 감사 대상 추출 실패가 비즈니스 흐름이나 감사 기록 자체를 막지 않도록 한다.
            log.warn("감사 target SpEL 평가 실패 (action={}, expr={}): {}",
                    audited.action(), expression, e.getMessage());
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= TARGET_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, TARGET_MAX_LENGTH);
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "ANONYMOUS";
        }
        return auth.getName();
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return clientIpResolver.resolve(attrs.getRequest());
        }
        return null;
    }

    /** 대상 식별자 best-effort: 첫 번째 String 인자(예: 계좌번호). */
    private String firstStringArg(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
