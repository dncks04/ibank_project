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
    private final RefreshTokenReuseHandler reuseHandler;
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
     * 리프레시 토큰 검증 후 회전(기존 폐기 + 사용자 반환). 호출자는 반환된 사용자로 새 토큰을 발급한다.
     *
     * <p><b>비관적 락</b>: 회전은 check-then-act(폐기 여부 확인 → 회전)이므로 행 락으로 직렬화한다.
     * 락이 없으면 동일 토큰 동시 /refresh가 둘 다 {@code revoked=false}를 읽고 둘 다 회전에 성공해
     * 토큰 패밀리가 갈라진다.
     *
     * <p>재사용 탐지: 이미 회전으로 폐기된 토큰이 다시 들어오면 탈취로 간주해 전체 세션을 무효화한다
     * ({@link RefreshTokenReuseHandler}). 단, 회전 직후 유예창 안의 재제출은 정상 동시 재시도로 보고
     * 무효화 없이 단순 거부한다(재시도/더블클릭 오탐 방지). 로그아웃으로 폐기된 토큰의 재제출도 단순 거부한다.
     */
    @Transactional
    public User rotate(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHashWithLock(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (token.isRevoked()) {
            // 회전으로 폐기된 토큰의 재사용만 탈취 신호. 단, 유예창 안의 재제출은 동시 재시도이므로 제외한다.
            Duration leeway = Duration.ofMillis(jwtProperties.refreshReuseLeewayMs());
            if (token.isRotated() && !token.isWithinReuseLeeway(LocalDateTime.now(), leeway)) {
                reuseHandler.handleReuse(token.getUser()); // 전체 세션 무효화 + 경보 (별도 트랜잭션 커밋)
            }
            throw new InvalidRefreshTokenException();
        }

        if (token.isExpired(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }

        token.markRotated();
        return token.getUser();
    }

    /** 리프레시 토큰 폐기(로그아웃). 존재하지 않아도 조용히 무시한다. */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(RefreshToken::revoke);
    }

    /** 특정 사용자의 모든 리프레시 토큰 폐기(전체 세션 무효화). */
    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
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
