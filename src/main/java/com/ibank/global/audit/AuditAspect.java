package com.ibank.global.audit;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    private final AuditService auditService;

    @Around("@annotation(com.ibank.global.audit.Audited)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Audited audited = signature.getMethod().getAnnotation(Audited.class);

        String actor = currentActor();
        String ip = currentIp();
        String target = firstStringArg(joinPoint.getArgs());

        try {
            Object result = joinPoint.proceed();
            auditService.record(actor, audited.action(), target, SUCCESS, null, ip);
            return result;
        } catch (Throwable t) {
            auditService.record(actor, audited.action(), target, FAILURE, t.getClass().getSimpleName(), ip);
            throw t;
        }
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
            return attrs.getRequest().getRemoteAddr();
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
