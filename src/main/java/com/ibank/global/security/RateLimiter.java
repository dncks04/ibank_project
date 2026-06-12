package com.ibank.global.security;

/**
 * 키(출발지 IP) 단위 토큰 버킷 호출 제한기.
 *
 * <p>구현은 배포 형태에 따라 선택한다 — {@code ibank.redis.enabled}:
 * <ul>
 *   <li>{@code false}(기본): {@link InMemoryRateLimiter} — 단일 인스턴스, 외부 의존 없음</li>
 *   <li>{@code true}: {@link RedisRateLimiter} — 다중 인스턴스가 한도를 공유(Lua로 원자 처리)</li>
 * </ul>
 */
public interface RateLimiter {

    /** 호출 허용 여부와, 거부 시 재시도까지 남은 초. */
    record Probe(boolean allowed, long retryAfterSeconds) {}

    /** 키(IP) 버킷에서 토큰 1개를 소비 시도한다. */
    Probe tryConsume(String key);
}
