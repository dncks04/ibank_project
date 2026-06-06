package com.ibank.controller;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.UserResponse;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.exception.DuplicateLoginIdException;
import com.ibank.domain.user.exception.InvalidCredentialsException;
import com.ibank.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class UserControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @MockitoBean UserService userService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("회원가입 성공 - 201 반환")
    void register_success() throws Exception {
        UserResponse response = new UserResponse(1L, "testuser", "테스터",
                "test@test.com", User.UserRole.ROLE_USER, LocalDateTime.now());
        given(userService.register(any())).willReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser",
                                  "password": "password123",
                                  "name": "테스터",
                                  "email": "test@test.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.loginId").value("testuser"));
    }

    @Test
    @DisplayName("회원가입 - loginId 4자 미만 시 400 반환")
    void register_validationFail_shortLoginId() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "ab",
                                  "password": "password123",
                                  "name": "테스터",
                                  "email": "test@test.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("회원가입 - 이메일 형식 오류 시 400 반환")
    void register_validationFail_invalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser",
                                  "password": "password123",
                                  "name": "테스터",
                                  "email": "not-an-email"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("회원가입 - 중복 아이디 시 400 반환")
    void register_duplicateLoginId() throws Exception {
        willThrow(new DuplicateLoginIdException())
                .given(userService).register(any());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser",
                                  "password": "password123",
                                  "name": "테스터",
                                  "email": "test@test.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));
    }

    @Test
    @DisplayName("로그인 성공 - 200 반환 및 토큰 포함")
    void login_success() throws Exception {
        given(userService.login(any(), any()))
                .willReturn(LoginResponse.of("jwt-token", "testuser", "테스터"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("로그인 - 잘못된 자격증명 시 400 반환")
    void login_wrongCredentials() throws Exception {
        willThrow(new InvalidCredentialsException())
                .given(userService).login(any(), any());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser",
                                  "password": "wrongpass"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("로그인 - password 누락 시 400 반환")
    void login_missingPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginId": "testuser"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
