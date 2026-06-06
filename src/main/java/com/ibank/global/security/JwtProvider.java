package com.ibank.global.security;

import com.ibank.global.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;
    private final long accessExpirationMs;

    public JwtProvider(JwtProperties props) {
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        // JJWT는 HS256에 최소 256bit(32 bytes) 요구
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret은 최소 32자 이상이어야 합니다.");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpirationMs = props.accessExpirationMs();
    }

    /** 짧은 수명의 access 토큰 발급 (인증용). 리프레시 토큰은 별도(불투명) 관리. */
    public String generateAccessToken(String loginId, String role, long tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(loginId)
                .claim("role", role)
                .claim("tv", tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰을 1회만 검증·파싱하여 클레임을 반환한다. 유효하지 않으면 {@code null}.
     *
     * 인증 필터의 핫 패스에서 서명 검증이 요청당 한 번만 일어나도록, 개별 게터를
     * 여러 번 호출(=여러 번 파싱)하는 대신 이 메서드로 클레임을 한 번 얻어 재사용한다.
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("만료된 JWT: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 JWT: {}", e.getMessage());
        }
        return null;
    }

    public String getLoginId(Claims claims) {
        return claims.getSubject();
    }

    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }

    /** 토큰의 무효화 버전(tv) 클레임. 구버전 토큰 등 없으면 0으로 간주. */
    public long getTokenVersion(Claims claims) {
        Long tv = claims.get("tv", Long.class);
        return tv != null ? tv : 0L;
    }
}
