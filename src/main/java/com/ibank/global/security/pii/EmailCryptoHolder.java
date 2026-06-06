package com.ibank.global.security.pii;

/**
 * JPA 컨버터·엔티티 리스너처럼 Spring이 주입하기 까다로운 지점에서 {@link EmailCrypto}에 접근하기 위한 정적 다리.
 * 기동 시 {@link EmailCrypto#register()}가 한 번 설정한다(읽기 전용 공유).
 */
public final class EmailCryptoHolder {

    private static volatile EmailCrypto instance;

    private EmailCryptoHolder() {}

    static void set(EmailCrypto crypto) {
        instance = crypto;
    }

    public static EmailCrypto get() {
        EmailCrypto crypto = instance;
        if (crypto == null) {
            throw new IllegalStateException("EmailCrypto가 아직 초기화되지 않았습니다.");
        }
        return crypto;
    }
}
