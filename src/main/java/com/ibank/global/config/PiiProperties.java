package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PII(개인식별정보) 보호용 비밀키. 이 한 개의 비밀에서 암호화 키와 blind index 키를 파생한다.
 * 운영(prod)에서는 기본값 없이 환경변수로만 주입한다(미설정 시 기동 실패, fail-fast).
 */
@ConfigurationProperties(prefix = "pii")
public record PiiProperties(
        String secret
) {}
