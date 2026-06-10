package com.ibank.domain.transaction.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 멱등성 요청 지문(fingerprint) 생성기.
 *
 * 같은 멱등성 키로 들어온 재요청이 "정말 같은 요청"인지 식별하기 위해, 거래 유형·계좌·금액 등
 * 요청의 핵심 필드를 정규화해 SHA-256 해시로 만든다. 재요청 시 저장된 지문과 비교하여,
 * 일치하면 기존 결과를 replay하고 불일치하면 키 충돌(409)로 거부한다(키 재사용 공격/버그 방어).
 */
public final class IdempotencyFingerprint {

    private IdempotencyFingerprint() {
    }

    /** 요청 핵심 필드들을 구분자로 결합해 SHA-256 16진수 지문을 만든다. */
    public static String of(String... parts) {
        String canonical = String.join("|", parts);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 보장하므로 정상적으로 도달하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 금액 정규화: scale가 달라도(예: 1000 vs 1000.00) 동일 지문이 되도록 후행 0을 제거한다.
     * 같은 금액의 표기 차이로 멱등 요청이 충돌로 오판되지 않게 한다.
     */
    public static String normalizeAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
