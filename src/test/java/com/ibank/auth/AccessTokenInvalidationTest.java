package com.ibank.auth;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * access 토큰 즉시 무효화 검증. 실제 JWT가 보안 필터 체인을 통과하는 전 구간 테스트.
 * 전체 세션 무효화 후 기존 access 토큰(stateless JWT)이 즉시 거부되는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class AccessTokenInvalidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        userService.register(new RegisterRequest("invuser", "password123", "무효화", "inv@test.com"));
    }

    private String login() {
        LoginResponse res = userService.login(new LoginRequest("invuser", "password123"));
        return res.accessToken();
    }

    @Test
    @DisplayName("전체 세션 무효화 후 기존 access 토큰은 즉시 거부되고, 재로그인 토큰은 정상 동작한다")
    void invalidate_rejectsExistingAccessToken() throws Exception {
        String token = login();

        // 무효화 전: 보호 자원 접근 OK
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 본인 토큰으로 전체 세션 무효화
        mockMvc.perform(post("/api/auth/sessions/invalidate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 같은 access 토큰은 이제 즉시 무효 (토큰 버전 불일치)
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 재로그인하면 새 버전 토큰으로 정상 접근
        String newToken = login();
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("세션 무효화 엔드포인트는 인증이 필요하다 (permitAll /api/auth/** 보다 우선)")
    void invalidate_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/sessions/invalidate"))
                .andExpect(status().isUnauthorized());
    }
}
