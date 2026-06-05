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
import com.ibank.global.security.JwtProvider;
import com.ibank.global.security.LoginAttemptService;
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

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }
        if (userRepository.existsByEmail(request.email())) {
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

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String loginId = request.loginId();

        // brute-force 방어: 임계치 초과 시 일시 잠금
        if (loginAttemptService.isLocked(loginId)) {
            throw new TooManyLoginAttemptsException(loginId);
        }

        User user = userRepository.findByLoginId(loginId).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            loginAttemptService.recordFailure(loginId);
            throw new InvalidCredentialsException();
        }

        loginAttemptService.reset(loginId);

        String accessToken = jwtProvider.generateAccessToken(user.getLoginId(), user.getRole().name());
        String refreshToken = refreshTokenService.issue(user);
        return LoginResponse.of(accessToken, refreshToken, user.getLoginId(), user.getName());
    }

    /** 리프레시 토큰 회전: 기존 토큰을 폐기하고 새 access/refresh를 발급한다. */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        User user = refreshTokenService.rotate(rawRefreshToken);
        String accessToken = jwtProvider.generateAccessToken(user.getLoginId(), user.getRole().name());
        String newRefreshToken = refreshTokenService.issue(user);
        return TokenResponse.of(accessToken, newRefreshToken);
    }

    /** 로그아웃: 리프레시 토큰 폐기. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }
}
