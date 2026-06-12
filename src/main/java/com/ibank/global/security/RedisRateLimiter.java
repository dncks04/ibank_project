package com.ibank.global.security;

import com.ibank.global.config.RateLimitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 기반 토큰 버킷 구현 — 다중 인스턴스가 한도를 공유한다.
 *
 * <p>보충·소비·잔량 판정을 Lua 스크립트 한 번으로 처리해 원자성을 보장한다
 * (read-modify-write를 나눠 보내면 동시 요청이 같은 잔량을 읽고 각각 소비하는 레이스가 생긴다).
 *
 * <p>시각은 앱이 전달한다(스크립트 결정성 유지). 인스턴스 간 시계 오차만큼 보충량이 흔들릴 수
 * 있으나 NTP 동기화 환경에서 무시할 수준이다. 키는 보충 주기의 2배로 만료시켜 유휴 IP가
 * Redis에 누적되지 않게 한다(만료 후 재방문은 가득 찬 버킷으로 시작하므로 상태 손실이 없다).
 */
@Component
@ConditionalOnProperty(name = "ibank.redis.enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "ibank:rate-limit:";

    /**
     * KEYS[1]=버킷 키, ARGV: capacity, refillTokens, refillPeriodMs, nowMs
     * 반환: {allowed(0|1), retryAfterSeconds}
     */
    private static final String LUA = """
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local capacity = tonumber(ARGV[1])
            local refill_tokens = tonumber(ARGV[2])
            local period_ms = tonumber(ARGV[3])
            local now_ms = tonumber(ARGV[4])
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now_ms
            end
            local elapsed = now_ms - ts
            if elapsed > 0 then
              tokens = math.min(capacity, tokens + elapsed * refill_tokens / period_ms)
            end
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
            redis.call('PEXPIRE', KEYS[1], period_ms * 2)
            local retry_after = 0
            if allowed == 0 then
              retry_after = math.ceil((1 - tokens) * period_ms / refill_tokens / 1000)
              if retry_after < 1 then retry_after = 1 end
            end
            return {allowed, retry_after}
            """;

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties props;

    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties props) {
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Probe tryConsume(String key) {
        List<Long> result = redisTemplate.execute(SCRIPT,
                List.of(KEY_PREFIX + key),
                String.valueOf(props.capacity()),
                String.valueOf(props.refillTokens()),
                String.valueOf(Math.max(1L, props.refillPeriod().toMillis())),
                String.valueOf(System.currentTimeMillis()));
        boolean allowed = result.get(0) == 1L;
        return new Probe(allowed, allowed ? 0 : result.get(1));
    }
}
