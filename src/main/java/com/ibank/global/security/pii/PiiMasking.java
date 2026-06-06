package com.ibank.global.security.pii;

/**
 * PII 표시용 마스킹. 키가 필요 없는 순수 변환이므로 정적 유틸로 둔다.
 * API 응답 등 외부 노출 시 원문 대신 마스킹된 값을 사용한다.
 */
public final class PiiMasking {

    private PiiMasking() {}

    /**
     * email 마스킹. 예: {@code test@test.com} → {@code te**@t***.com}.
     * 로컬part는 앞 2자(짧으면 1자)만, 도메인은 호스트 첫 글자만 남기고 TLD는 보존한다.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return maskTail(email, 1);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        String maskedLocal = local.length() <= 2
                ? maskTail(local, 1)
                : maskTail(local, 2);

        int dot = domain.lastIndexOf('.');
        String maskedDomain = (dot < 1)
                ? maskTail(domain, 1)
                : maskTail(domain.substring(0, dot), 1) + domain.substring(dot);

        return maskedLocal + "@" + maskedDomain;
    }

    /** 앞 {@code keep}자만 남기고 나머지를 '*'로. */
    private static String maskTail(String s, int keep) {
        if (s.length() <= keep) {
            return "*".repeat(Math.max(1, s.length()));
        }
        return s.substring(0, keep) + "*".repeat(s.length() - keep);
    }
}
