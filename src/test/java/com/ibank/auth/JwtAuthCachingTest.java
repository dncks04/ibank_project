package com.ibank.auth;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 핫패스 캐싱 검증. stateless JWT의 강점(요청마다 DB를 타지 않음)이 유지되는지,
 * 무효화 시에는 캐시가 비워져 즉시성이 보장되는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class JwtAuthCachingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @Autowired UserService userService;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired CacheManager cacheManager;
    @MockitoSpyBean UserRepository userRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.USER_DETAILS_CACHE)).clear();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        userService.register(new RegisterRequest("cacheuser", "password123", "캐시", "cache@test.com"));
    }

    private String login() {
        LoginResponse res = userService.login(new LoginRequest("cacheuser", "password123"));
        return res.accessToken();
    }

    @Test
    @DisplayName("같은 토큰으로 반복 인증해도 users 테이블은 첫 요청에서 한 번만 조회된다")
    void repeatedAuth_hitsDbOnlyOnce() throws Exception {
        String token = login();
        clearInvocations(userRepository); // 로그인 단계의 조회를 제외하고 인증 핫패스만 측정

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        // 5회 인증했지만 캐시 덕분에 DB 조회는 첫 미스 1회뿐이다
        verify(userRepository, times(1)).findByLoginId("cacheuser");
    }

    @Test
    @DisplayName("무효화 시 캐시가 비워져 다음 요청은 DB를 다시 읽고 옛 토큰을 거부한다")
    void invalidation_evictsCache_andRejectsOldToken() throws Exception {
        String token = login();

        // 캐시 적재
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 전체 세션 무효화 (커밋 후 캐시 evict)
        mockMvc.perform(post("/api/auth/sessions/invalidate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        clearInvocations(userRepository);

        // 캐시가 비워졌으므로 DB를 다시 읽고(조회 1회), 옛 토큰 버전은 거부된다
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verify(userRepository, times(1)).findByLoginId("cacheuser");
    }
}
