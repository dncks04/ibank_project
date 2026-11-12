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

    /**
     * 성공 시 감사 로그의 detail에 남길 값을 추출하는 SpEL 표현식. target과 같은 문맥(파라미터, <code>#result</code>)을 쓴다.
     * 비워두면 성공 감사의 detail은 비어 있다. 실패 감사의 detail은 항상 예외 종류다.
     * 예: 보류 거래 실행·취소 사유 <code>"#reasonCode"</code>.
     */
    String detail() default "";
}
