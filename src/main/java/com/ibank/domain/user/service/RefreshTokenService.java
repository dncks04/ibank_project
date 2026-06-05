package com.ibank.domain.user.service;

import com.ibank.domain.user.entity.RefreshToken;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.exception.InvalidRefreshTokenException;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.global.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 리프레시 토큰 발급/회전/폐기.
 *
 * - 원문(raw)은 클라이언트에만 전달하고 DB에는 SHA-256 해시만 저장한다.
 * - 회전(rotation): /refresh 시 기존 토큰을 폐기하고 새 토큰을 발급한다.
 *   탈취된 토큰의 재사용 창을 줄인다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    /** 새 리프레시 토큰 발급. 반환값은 클라이언트에 전달할 원문. */
    @Transactional
    public String issue(User user) {
        String raw = generateRawToken();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .expiresAt(LocalDateTime.now().plus(Duration.ofMillis(jwtProperties.refreshExpirationMs())))
                .build();
        refreshTokenRepository.save(token);
        return raw;
    }

    /**
     * 리프레시 토큰 검증 후 폐기(회전). 유효하면 소유 사용자를 반환한다.
     * 호출자는 반환된 사용자로 새 access/refresh 토큰을 발급한다.
     */
    @Transactional
    public User rotate(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!token.isActive(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }
        token.revoke();
        return token.getUser();
    }

    /** 리프레시 토큰 폐기(로그아웃). 존재하지 않아도 조용히 무시한다. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(RefreshToken::revoke);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
