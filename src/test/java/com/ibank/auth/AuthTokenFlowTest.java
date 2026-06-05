package com.ibank.auth;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.TokenResponse;
import com.ibank.domain.user.exception.InvalidCredentialsException;
import com.ibank.domain.user.entity.RefreshToken;
import com.ibank.domain.user.exception.InvalidRefreshTokenException;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.audit.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuthTokenFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(new RegisterRequest("authuser", "password123", "인증유저", "auth@test.com"));
    }

    private LoginResponse login() {
        return userService.login(new LoginRequest("authuser", "password123"));
    }

    @Test
    @DisplayName("로그인 시 access/refresh 토큰이 모두 발급된다")
    void login_issuesBothTokens() {
        LoginResponse response = login();

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("리프레시 - 새 토큰 발급 및 기존 리프레시 토큰 폐기(회전)")
    void refresh_rotatesToken() {
        LoginResponse login = login();

        TokenResponse refreshed = userService.refresh(login.refreshToken());

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken())
                .isNotBlank()
                .isNotEqualTo(login.refreshToken());

        // 회전된 기존 토큰은 더 이상 사용할 수 없다
        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("로그아웃 - 리프레시 토큰이 폐기되어 재사용 불가")
    void logout_revokesRefreshToken() {
        LoginResponse login = login();

        userService.logout(login.refreshToken());

        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("회전된 토큰 재사용 시 탈취로 간주해 전체 세션을 무효화하고 감사 경보를 남긴다")
    void refresh_reuseOfRotatedToken_revokesAllSessionsAndAlerts() {
        LoginResponse login = login();
        TokenResponse rotated = userService.refresh(login.refreshToken()); // T1 → T2 (T1 rotated)

        // 이미 회전된 T1을 재사용 (탈취 시나리오) → 거부
        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // 전체 세션 무효화: 정상 사용자의 새 토큰(T2)도 더는 쓸 수 없다
        assertThatThrownBy(() -> userService.refresh(rotated.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(refreshTokenRepository.findAll()).allMatch(RefreshToken::isRevoked);

        // 보안 경보가 감사 로그에 남는다
        assertThat(auditLogRepository.findAll())
                .anyMatch(a -> "REFRESH_TOKEN_REUSE".equals(a.getAction()) && "FAILURE".equals(a.getResult()));
    }

    @Test
    @DisplayName("로그아웃으로 폐기된 토큰 재제출은 단순 거부일 뿐 재사용 경보를 만들지 않는다")
    void refresh_reuseOfLoggedOutToken_noAlert() {
        LoginResponse login = login();
        userService.logout(login.refreshToken());

        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // 로그아웃은 탈취가 아니므로 재사용 경보(REFRESH_TOKEN_REUSE) 없음
        assertThat(auditLogRepository.findAll())
                .noneMatch(a -> "REFRESH_TOKEN_REUSE".equals(a.getAction()));
    }

    @Test
    @DisplayName("존재하지 않는 리프레시 토큰은 거부된다")
    void refresh_unknownToken_rejected() {
        assertThatThrownBy(() -> userService.refresh("non-existent-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("잘못된 비밀번호는 InvalidCredentialsException")
    void login_wrongPassword() {
        assertThatThrownBy(() -> userService.login(new LoginRequest("authuser", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
