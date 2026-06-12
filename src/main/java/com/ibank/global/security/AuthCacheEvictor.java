package com.ibank.global.security;

import com.ibank.global.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 토큰 버전 증가(즉시 무효화) 시 인증 캐시를 비운다.
 *
 * <p><b>커밋 이후 evict</b>: 트랜잭션 커밋 전에 비우면, 동시 인증 요청이 아직 커밋되지 않은 옛 토큰 버전을
 * 다시 읽어 캐시를 재적재할 수 있다(무효화가 새지 못함). 따라서 커밋 이후에 비운다. 트랜잭션 밖에서
 * 호출되면 즉시 비운다.
 *
 * <p>무효화는 드문 보안 이벤트(패닉 로그아웃/탈취 탐지)이므로 전체 비움(clear)으로 단순·견고하게 처리한다.
 * 대상 사용자는 확실히 evict되어 즉시 무효화가 보장된다.
 *
 * <p>Redis가 켜져 있으면({@code ibank.redis.enabled=true}) 무효화 신호를 브로드캐스트해
 * 다른 인스턴스의 로컬 캐시도 함께 비운다 — {@link AuthCacheRedisConfig} 참조.
 */
@Component
@RequiredArgsConstructor
public class AuthCacheEvictor {

    private final CacheManager cacheManager;
    private final ObjectProvider<AuthCacheRedisConfig.AuthCacheInvalidationBroadcaster> broadcaster;

    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow();
                }
            });
        } else {
            evictNow();
        }
    }

    private void evictNow() {
        Cache cache = cacheManager.getCache(CacheConfig.USER_DETAILS_CACHE);
        if (cache != null) {
            cache.clear();
        }
        // 다중 인스턴스: 다른 인스턴스의 로컬 캐시도 비우도록 신호를 발행한다 (Redis 비활성 시 no-op)
        broadcaster.ifAvailable(AuthCacheRedisConfig.AuthCacheInvalidationBroadcaster::broadcast);
    }
}
