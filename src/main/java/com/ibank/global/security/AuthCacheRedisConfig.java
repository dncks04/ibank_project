package com.ibank.global.security;

import com.ibank.global.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 인증 캐시 무효화의 다중 인스턴스 전파 (Redis pub/sub).
 *
 * <p>인증 캐시는 핫패스 성능을 위해 로컬(Caffeine)을 유지한다 — 캐시 데이터를 Redis로
 * 옮기는 대신 <b>무효화 신호만</b> 공유한다. 토큰 버전 증가(전체 세션 무효화·재사용 탐지) 시
 * 모든 인스턴스가 이 채널을 구독하고 있다가 각자 로컬 캐시를 비우므로, 어느 인스턴스로
 * 라우팅되든 옛 토큰 버전이 캐시에서 살아남지 못한다.
 *
 * <p>pub/sub는 at-most-once라 구독 단절 중 신호를 놓칠 수 있으나, 캐시 TTL(기본 30초)이
 * 지연 상한을 보장하는 2차 방어선으로 남는다.
 */
@Configuration
@ConditionalOnProperty(name = "ibank.redis.enabled", havingValue = "true")
public class AuthCacheRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthCacheRedisConfig.class);

    /** 인증 캐시 무효화 브로드캐스트 채널. */
    public static final String CHANNEL = "ibank:auth-cache:invalidate";

    @Bean
    public RedisMessageListenerContainer authCacheInvalidationListener(
            RedisConnectionFactory connectionFactory, CacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            Cache cache = cacheManager.getCache(CacheConfig.USER_DETAILS_CACHE);
            if (cache != null) {
                cache.clear();
                log.info("인증 캐시 무효화 신호 수신 — 로컬 캐시 비움");
            }
        }, new ChannelTopic(CHANNEL));
        return container;
    }

    @Bean
    public AuthCacheInvalidationBroadcaster authCacheInvalidationBroadcaster(StringRedisTemplate redisTemplate) {
        return new AuthCacheInvalidationBroadcaster(redisTemplate);
    }

    /** 무효화 신호 발행자. 수신 측(위 listener)이 발행 인스턴스를 포함해 전부 로컬 캐시를 비운다. */
    public static class AuthCacheInvalidationBroadcaster {

        private final StringRedisTemplate redisTemplate;

        AuthCacheInvalidationBroadcaster(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        public void broadcast() {
            redisTemplate.convertAndSend(CHANNEL, "invalidate");
        }
    }
}
