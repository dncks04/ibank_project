package com.ibank.redis;

import com.ibank.global.config.CacheConfig;
import com.ibank.global.security.AuthCacheRedisConfig;
import com.ibank.global.security.RateLimiter;
import com.ibank.global.security.RedisRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 연동 검증 ({@code ibank.redis.enabled=true}).
 *
 * <p>분산 rate limit: 동시 요청이 몰려도 Lua 원자 처리로 정확히 capacity 건만 허용되는지,
 * 인증 캐시 무효화: 다른 인스턴스가 발행한 신호(pub/sub)에 로컬 캐시가 비워지는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "ibank.redis.enabled=true",
        "ibank.rate-limit.capacity=5",
        "ibank.rate-limit.refill-tokens=5",
        "ibank.rate-limit.refill-period=1m"
})
class RedisIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired RateLimiter rateLimiter;
    @Autowired CacheManager cacheManager;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Redis가 켜지면 RateLimiter 구현은 RedisRateLimiter다")
    void redisRateLimiterIsWired() {
        assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
    }

    @Test
    @DisplayName("동시 요청 20건 중 정확히 capacity(5)건만 허용된다 — Lua 원자성")
    void concurrentConsume_allowsExactlyCapacity() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        int threads = 20;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (rateLimiter.tryConsume(key).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("거부된 요청은 1초 이상의 Retry-After를 받는다")
    void rejected_hasRetryAfter() {
        String key = "retry-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(key);
        }
        RateLimiter.Probe probe = rateLimiter.tryConsume(key);
        assertThat(probe.allowed()).isFalse();
        assertThat(probe.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("다른 인스턴스의 무효화 신호(pub/sub)에 로컬 인증 캐시가 비워진다")
    void invalidationBroadcast_clearsLocalCache() throws Exception {
        Cache cache = cacheManager.getCache(CacheConfig.USER_DETAILS_CACHE);
        assertThat(cache).isNotNull();
        cache.put("some-user", "cached-value");
        assertThat(cache.get("some-user")).isNotNull();

        // 다른 인스턴스가 무효화를 발행한 상황을 재현 (수신 listener가 로컬 캐시를 비워야 한다)
        redisTemplate.convertAndSend(AuthCacheRedisConfig.CHANNEL, "invalidate");

        long deadline = System.currentTimeMillis() + 5_000;
        while (cache.get("some-user") != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(cache.get("some-user")).isNull();
    }
}
