package com.ibank.global.security;

import com.ibank.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Duration;
import java.util.Date;

/**
 * access 토큰 발급·검증. RS256으로 서명하고 헤더의 {@code kid}로 검증 키를 고른다({@link JwtKeys}).
 *
 * <p>토큰은 두 종류다. {@code typ} 클레임으로 구분하며, 한쪽 토큰이 다른 쪽으로 쓰이지 않게 필터가 종류를 확인한다.
 * <ul>
 *   <li>{@code user}: 고객·운영자 토큰. sub=loginId, role, tv(무효화 버전)</li>
 *   <li>{@code system}: 서비스 간 호출용 토큰. sub=클라이언트 ID, role=ROLE_SYSTEM. 사용자 행이 없으므로 tv도 없다</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtProvider {

    static final String CLAIM_TYPE = "typ";
    static final String TYPE_USER = "user";
    static final String TYPE_SYSTEM = "system";
    public static final String ROLE_SYSTEM = "ROLE_SYSTEM";

    private final JwtKeys keys;
    private final long accessExpirationMs;

    public JwtProvider(JwtProperties props, JwtKeys keys) {
        this.keys = keys;
        this.accessExpirationMs = props.accessExpirationMs();
    }

    /** 짧은 수명의 access 토큰 발급 (인증용). 리프레시 토큰은 별도(불투명) 관리. */
    public String generateAccessToken(String loginId, String role, long tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(keys.signingKeyId()).and()
                .subject(loginId)
                .claim(CLAIM_TYPE, TYPE_USER)
                .claim("role", role)
                .claim("tv", tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(keys.signingKey(), Jwts.SIG.RS256)
                .compact();
    }

    /** 서비스 간 호출용 시스템 토큰 발급. 수명은 짧게 두고 만료되면 다시 발급받게 한다. */
    public String generateSystemToken(String clientId, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                .header().keyId(keys.signingKeyId()).and()
                .subject(clientId)
                .claim(CLAIM_TYPE, TYPE_SYSTEM)
                .claim("role", ROLE_SYSTEM)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(keys.signingKey(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 토큰을 1회만 검증·파싱하여 클레임을 반환한다. 유효하지 않으면 {@code null}.
     *
     * 인증 필터의 핫 패스에서 서명 검증이 요청당 한 번만 일어나도록, 개별 게터를
     * 여러 번 호출(=여러 번 파싱)하는 대신 이 메서드로 클레임을 한 번 얻어 재사용한다.
     *
     * 검증 키는 RSA 공개키뿐이므로, 서명이 없는 토큰이나 대칭키(HS256)로 서명한 토큰은 통과하지 못한다.
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            Key key = keys.verificationKey(header.getKeyId());
                            if (key == null) {
                                throw new JwtException("알 수 없는 kid");
                            }
                            return key;
                        }
                    })
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

    /** 시스템 토큰인지. typ 클레임이 없는 토큰은 사용자 토큰으로 본다. */
    public boolean isSystemToken(Claims claims) {
        return TYPE_SYSTEM.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /** 토큰의 무효화 버전(tv) 클레임. 구버전 토큰 등 없으면 0으로 간주. */
    public long getTokenVersion(Claims claims) {
        Long tv = claims.get("tv", Long.class);
        return tv != null ? tv : 0L;
    }
}
