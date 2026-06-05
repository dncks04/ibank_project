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

    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("만료된 JWT: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 JWT: {}", e.getMessage());
        }
        return false;
    }

    public String getLoginId(String token) {
        return parseClaims(token).getSubject();
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /** 토큰의 무효화 버전(tv) 클레임. 구버전 토큰 등 없으면 0으로 간주. */
    public long getTokenVersion(String token) {
        Long tv = parseClaims(token).get("tv", Long.class);
        return tv != null ? tv : 0L;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
