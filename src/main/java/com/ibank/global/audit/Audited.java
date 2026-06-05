package com.ibank.global.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 메서드 실행을 감사 로그로 남긴다. {@link AuditAspect}가 처리.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
    /** 감사 액션명 (예: TRANSFER, ACCOUNT_OPEN). */
    String action();
}
