package com.ibank.global.config;

import com.ibank.global.security.JwtAuthenticationFilter;
import com.ibank.global.security.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, PiiProperties.class, RateLimitProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 전체 세션 무효화는 인증된 본인만 (permitAll보다 먼저 매칭되어야 함)
                .requestMatchers(HttpMethod.POST, "/api/auth/sessions/invalidate").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                // actuator는 분리된 관리 포트(management.server.port)에서만 서빙되며,
                // 운영(compose)에서는 그 포트를 호스트에 공개하지 않는다(네트워크 레벨 보호).
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                // API 문서: 운영(prod)에서는 springdoc 자체가 비활성화되어 404가 된다
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // 운영자 전용 API는 ADMIN 권한 필요 (현재 등록된 엔드포인트는 없다 — 경로 규칙만 미리 닫아 둔다)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 토큰 서명 공개키: 다른 서비스가 토큰을 직접 검증할 때 쓴다. 공개키뿐이라 열어 둔다
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                // 내부 API(서비스 간 호출): 시스템 토큰 발급만 열려 있고, 나머지는 ROLE_SYSTEM 토큰만 통과한다
                .requestMatchers(HttpMethod.POST, "/internal/auth/token").permitAll()
                .requestMatchers("/internal/**").hasRole("SYSTEM")
                // 고객·운영자 API는 사용자 토큰만. 시스템 토큰은 인증은 되지만 여기서 403이 된다
                // (고객 API는 요청자를 고객으로 전제하므로 서비스 계정이 들어오면 안 된다)
                // 창구 API는 창구 직원만. 직원은 고객 API를 쓰지 못한다(고객 API는 요청자를 계좌 주인으로 전제한다)
                .requestMatchers("/api/teller/**").hasRole("TELLER")
                .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                    sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "인증이 필요합니다.", "UNAUTHORIZED"))
                .accessDeniedHandler((request, response, e) ->
                    sendError(response, HttpServletResponse.SC_FORBIDDEN, "접근 권한이 없습니다.", "FORBIDDEN"))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // 호출 제한은 인증보다 먼저: 과도한 요청을 인증·DB 작업 전에 차단한다.
            .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** message와 code는 위의 상수 문자열만 들어온다(요청 값이 섞이지 않으므로 이스케이프가 필요 없다). */
    private void sendError(HttpServletResponse response, int status, String message, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
            "{\"success\":false,\"data\":null,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}"
        );
    }
}
