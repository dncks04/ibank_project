package com.ibank.global.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // 토큰은 요청당 한 번만 검증·파싱하고, 이후 클레임을 재사용한다(서명 검증 1회).
        Claims claims = token != null ? jwtProvider.parse(token) : null;
        if (claims != null) {
            UsernamePasswordAuthenticationToken auth = jwtProvider.isSystemToken(claims)
                    ? authenticateSystem(claims)
                    : authenticateUser(claims);
            if (auth != null) {
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 시스템 토큰: 서명과 만료만 확인한다. 사용자 행이 없으므로 조회하지 않는다.
     * 권한은 토큰의 role 클레임이 아니라 토큰 종류로 정한다 — 시스템 토큰은 언제나 ROLE_SYSTEM 하나뿐이다.
     */
    private UsernamePasswordAuthenticationToken authenticateSystem(Claims claims) {
        return new UsernamePasswordAuthenticationToken(
                new SystemPrincipal(claims.getSubject()),
                null,
                List.of(new SimpleGrantedAuthority(JwtProvider.ROLE_SYSTEM)));
    }

    /** 사용자 토큰: 사용자를 조회해 무효화 버전을 확인한다. 권한은 토큰이 아니라 DB의 역할을 따른다. */
    private UsernamePasswordAuthenticationToken authenticateUser(Claims claims) {
        CustomUserDetails userDetails;
        try {
            userDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(jwtProvider.getLoginId(claims));
        } catch (UsernameNotFoundException e) {
            return null; // 탈퇴 등으로 사용자가 없어진 토큰 — 인증하지 않는다
        }
        // 즉시 무효화 검사: 토큰 버전이 사용자 현재 버전과 다르면 폐기된 토큰 → 인증하지 않음
        if (jwtProvider.getTokenVersion(claims) != userDetails.getTokenVersion()) {
            return null;
        }
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
