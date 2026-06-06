package com.ibank.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 인증 핫패스 캐시.
 *
 * <p>JWT 인증 필터는 토큰 버전(즉시 무효화) 검사를 위해 요청마다 사용자를 조회한다. 그대로 두면
 * stateless JWT의 "DB 없이 인증" 강점이 사라지고 users 테이블이 인증 핫스팟이 된다. 사용자 조회 결과를
 * 짧은 TTL로 캐싱해 핫패스에서 DB 접근을 없애고, 무효화 시 evict로 즉시성을 유지한다.
 *
 * <p>로컬 캐시(Caffeine)이므로 다중 인스턴스에서는 TTL이 교차 인스턴스 무효화 지연의 상한이 된다.
 * 전역 즉시 무효화가 필요하면 Spring Cache 구현만 Redis로 교체하면 된다(코드 변경 없음).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** JWT 필터가 사용하는 사용자 인증 정보 캐시. */
    public static final String USER_DETAILS_CACHE = "userDetails";

    @Bean
    public CacheManager cacheManager(
            @Value("${security.auth-cache.ttl-seconds:30}") long ttlSeconds,
            @Value("${security.auth-cache.max-size:10000}") long maxSize) {
        // 명시한 캐시만 허용(동적 생성 비활성)해 의도치 않은 캐시 증식을 막는다.
        CaffeineCacheManager manager = new CaffeineCacheManager(USER_DETAILS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize));
        return manager;
    }
}
