package com.ibank.domain.user.service;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.TokenResponse;
import com.ibank.domain.user.dto.UserResponse;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.exception.DuplicateEmailException;
import com.ibank.domain.user.exception.DuplicateLoginIdException;
import com.ibank.domain.user.exception.InvalidCredentialsException;
import com.ibank.domain.user.exception.TooManyLoginAttemptsException;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.Audited;
import com.ibank.global.security.AuthCacheEvictor;
import com.ibank.global.security.JwtProvider;
import com.ibank.global.security.LoginAttemptService;
import com.ibank.global.security.pii.EmailCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final AuthCacheEvictor authCacheEvictor;
    private final EmailCrypto emailCrypto;

    /**
     * 존재하지 않는 사용자 로그인 시 timing attack(사용자 열거) 방지를 위한 더미 해시.
     * 사용자가 없어도 동일한 비용의 bcrypt 비교를 1회 수행해 응답 시간을 평준화한다.
     * 기동 시 한 번만 인코딩한다.
     */
    private String dummyPasswordHash;

    @jakarta.annotation.PostConstruct
    void initDummyHash() {
        this.dummyPasswordHash = passwordEncoder.encode("ibank-timing-equalizer");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }
        if (userRepository.existsByEmailBlindIndex(emailCrypto.blindIndex(request.email()))) {
            throw new DuplicateEmailException();
        }

        User user = User.builder()
                .loginId(request.loginId())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .email(request.email())
                .role(User.UserRole.ROLE_USER)
                .build();

        return UserResponse.from(userRepository.save(user));
    }

    /** IP 정보가 없는 경로(내부 호출/테스트)용 오버로드. */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        return login(request, null);
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        String loginId = request.loginId();

        // brute-force 방어: 계정 단위 + 출발지 IP 단위(분산 시도) 임계치 초과 시 일시 잠금
        if (loginAttemptService.isLocked(loginId)
                || (clientIp != null && loginAttemptService.isIpLocked(clientIp))) {
            throw new TooManyLoginAttemptsException(loginId);
        }

        User user = userRepository.findByLoginId(loginId).orElse(null);
        // 사용자가 없어도 더미 해시로 bcrypt 비교를 수행해 응답 시간을 일정하게 유지(사용자 열거 방지).
        String passwordHash = (user != null) ? user.getPassword() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(request.password(), passwordHash);
        if (user == null || !matches) {
            loginAttemptService.recordFailure(loginId);
            if (clientIp != null) {
                loginAttemptService.recordIpFailure(clientIp);
            }
            throw new InvalidCredentialsException();
        }

        loginAttemptService.reset(loginId);

        String accessToken = jwtProvider.generateAccessToken(
                user.getLoginId(), user.getRole().name(), user.getTokenVersion());
        String refreshToken = refreshTokenService.issue(user);
        return LoginResponse.of(accessToken, refreshToken, user.getLoginId(), user.getName());
    }

    /** 리프레시 토큰 회전: 기존 토큰을 폐기하고 새 access/refresh를 발급한다. */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        User user = refreshTokenService.rotate(rawRefreshToken);
        String accessToken = jwtProvider.generateAccessToken(
                user.getLoginId(), user.getRole().name(), user.getTokenVersion());
        String newRefreshToken = refreshTokenService.issue(user);
        return TokenResponse.of(accessToken, newRefreshToken);
    }

    /** 로그아웃: 리프레시 토큰 폐기. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    /**
     * 전체 세션 즉시 무효화(계정 도용 신고/패닉 로그아웃).
     * 토큰 버전을 올려 기존 access 토큰을 즉시 무효화하고, 모든 refresh 토큰을 폐기한다.
     */
    @Audited(action = "SESSION_INVALIDATE_ALL", target = "#userId")
    @Transactional
    public void invalidateAllSessions(Long userId) {
        userRepository.incrementTokenVersion(userId);
        refreshTokenService.revokeAll(userId);
        authCacheEvictor.evictAfterCommit(); // 캐시된 옛 토큰 버전 제거 → 즉시 무효화
    }
}
