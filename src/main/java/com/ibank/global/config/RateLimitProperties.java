package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 전역 호출 제한(rate limit) 설정. 출발지 IP 단위 토큰 버킷.
 *
 * <p>로그인은 실패 누적 기반 brute-force 방어({@code LoginAttemptService})가 별도로 있고,
 * 이 설정은 그 외 일반 API를 포함한 모든 {@code /api/**} 요청의 호출 빈도를 제한한다.
 *
 * <ul>
 *   <li>{@code capacity}: 순간 허용 버스트(버킷 용량)</li>
 *   <li>{@code refillTokens} / {@code refillPeriod}: 지속 처리율(주기마다 채워지는 토큰)</li>
 * </ul>
 *
 * <p>기본 비활성. 개발/테스트·부하측정에는 영향이 없도록 두고, 운영(prod 프로파일)에서 활성화한다.
 */
@ConfigurationProperties(prefix = "ibank.rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("100") int capacity,
        @DefaultValue("100") int refillTokens,
        @DefaultValue("1m") Duration refillPeriod
) {}
