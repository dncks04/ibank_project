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

    /**
     * 감사 대상(target)을 추출하는 SpEL 표현식.
     * 메서드 파라미터(이름으로 참조)와 성공 시 반환값(<code>#result</code>)을 참조할 수 있다.
     * 예: <code>"#request.accountNumber"</code>, <code>"#accountNumber"</code>, <code>"#result.accountNumber"</code>.
     * 비워두면 첫 번째 String 인자를 best-effort로 사용한다.
     */
    String target() default "";
}
