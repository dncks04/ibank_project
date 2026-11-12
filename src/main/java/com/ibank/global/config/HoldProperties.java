package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 홀드 설정.
 *
 * @param maxDuration 최대 보류 시간. 홀드가 이 시간을 넘기면 만료 배치가 풀어 준다.
 *                    실제 만료 정책(보호자 무응답 시 처리 등)은 FDS 서비스가 그 전에 실행·취소로 결정하고,
 *                    ibank는 FDS 서비스가 사라져도 돈이 영원히 묶이지 않도록 상한만 강제한다.
 */
@ConfigurationProperties(prefix = "ibank.holds")
public record HoldProperties(
        @DefaultValue("24h") Duration maxDuration
) {}
