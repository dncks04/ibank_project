package com.ibank.global.security;

import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * JWT 인증 필터가 요청마다 호출하는 핫패스. 결과를 짧은 TTL로 캐싱해 DB 접근을 없앤다(캐시 미스에만 조회).
     * 토큰 버전 변경(무효화) 시 {@code AuthCacheEvictor}가 캐시를 비워 즉시성을 유지한다.
     * 사용자 미존재는 예외이므로 캐싱되지 않는다(negative caching 없음).
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.USER_DETAILS_CACHE, key = "#loginId")
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        return userRepository.findByLoginId(loginId)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
