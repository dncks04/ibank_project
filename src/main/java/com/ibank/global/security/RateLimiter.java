package com.ibank.global.security;

import com.ibank.global.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 출발지 IP(키) 단위 토큰 버킷 호출 제한기.
 *
 * <p>버킷은 경과 시간에 비례해 토큰을 lazy 보충하고, 요청마다 1개를 소비한다.
 * 토큰이 없으면 거부하며, 다음 토큰까지 남은 시간을 {@code Retry-After}로 돌려준다.
 *
 * <p>단일 인스턴스 메모리 기반(ConcurrentHashMap). 다중 인스턴스 환경에서는
 * Redis 등 공유 저장소로 대체해야 한다 — {@code LoginAttemptService}와 동일한 한계.
 */
@Component
public class RateLimiter {

    // 메모리 무한증가(서로 다른 IP 대량 주입) 방어: 항목 수가 임계치를 넘으면 유휴 버킷을 스윕한다.
    static final int CLEANUP_THRESHOLD = 10_000;

    private final RateLimitProperties props;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitProperties props) {
        this.props = props;
    }

    /** 호출 허용 여부와, 거부 시 재시도까지 남은 초. */
    public record Probe(boolean allowed, long retryAfterSeconds) {}

    /** 키(IP) 버킷에서 토큰 1개를 소비 시도한다. */
    public Probe tryConsume(String key) {
        long now = System.nanoTime();
        double capacity = props.capacity();
        long periodNanos = Math.max(1L, props.refillPeriod().toNanos());
        double refillPerNano = (double) props.refillTokens() / periodNanos;

        boolean[] allowed = {false};
        double[] remaining = {0};
        buckets.compute(key, (k, existing) -> {
            Bucket bucket = (existing == null) ? new Bucket(capacity, now) : existing;
            double refill = (now - bucket.lastRefillNanos) * refillPerNano;
            if (refill > 0) {
                bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            }
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                allowed[0] = true;
            }
            remaining[0] = bucket.tokens;
            return bucket;
        });

        if (buckets.size() > CLEANUP_THRESHOLD) {
            cleanupStale(now, periodNanos);
        }

        if (allowed[0]) {
            return new Probe(true, 0);
        }
        // 다음 1토큰까지 남은 시간(초). 보충률이 0에 수렴하지 않도록 주기는 1초 이상으로 본다.
        double tokensPerSecond = (double) props.refillTokens() / Math.max(1L, props.refillPeriod().toSeconds());
        long retryAfter = (long) Math.ceil((1.0 - remaining[0]) / tokensPerSecond);
        return new Probe(false, Math.max(1L, retryAfter));
    }

    /** 마지막 사용 후 보충 주기를 넘긴 버킷은 어차피 가득 차므로 제거해도 상태 손실이 없다. */
    private void cleanupStale(long now, long periodNanos) {
        buckets.values().removeIf(b -> now - b.lastRefillNanos > periodNanos);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
