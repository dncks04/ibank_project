package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정.
 *
 * @param privateKey        토큰 서명용 RSA 개인키(PKCS#8 PEM). 비어 있으면 기동 시 임시 키를 만든다 —
 *                          개발·테스트용이며, 재기동하면 이전 토큰이 모두 무효가 되고 인스턴스마다 키가 달라진다.
 *                          운영 프로파일에서는 필수다.
 * @param previousPublicKey 키 교체 직전에 쓰던 RSA 공개키(X.509 PEM). 설정하면 그 키로 서명된 토큰도 검증에
 *                          통과하고 JWKS에도 함께 실린다. 교체 후 access 토큰 수명(15분)이 지나면 비운다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String privateKey,
        String previousPublicKey,
        long accessExpirationMs,
        long refreshExpirationMs,
        long refreshReuseLeewayMs
) {}
