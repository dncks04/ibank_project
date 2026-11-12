package com.ibank.auth;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.security.JwtKeys;
import com.ibank.global.security.JwtProvider;
import com.ibank.support.DatabaseCleaner;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 간 인증: 시스템 토큰 발급, RS256 서명과 JWKS, 토큰 종류별 접근 범위.
 * 실제 토큰이 보안 필터 체인을 통과하는 전 구간으로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ibank.internal.clients.fds-service=correct-horse-battery-staple",
        "ibank.internal.token-ttl=5m"
})
@Testcontainers(disabledWithoutDocker = true)
class SystemAuthTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CLIENT_ID = "fds-service";
    private static final String CLIENT_SECRET = "correct-horse-battery-staple";

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private WebApplicationContext wac;
    @Autowired private UserService userService;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JwtKeys jwtKeys;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        userService.register(new RegisterRequest(
                "sysauthuser", "password123", "고객", "sysauth@test.com", LocalDate.of(1990, 1, 1)));
    }

    // ---- 시스템 토큰 발급 ----

    @Test
    @DisplayName("등록된 클라이언트 ID·시크릿으로 ROLE_SYSTEM 토큰을 발급받는다")
    void issueSystemToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest(CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andReturn();

        String token = json(result).at("/data/accessToken").asString();
        Claims claims = jwtProvider.parse(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(CLIENT_ID);
        assertThat(jwtProvider.isSystemToken(claims)).isTrue();
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_SYSTEM");
        // 수명은 설정한 5분 근처
        assertThat(Duration.between(new Date().toInstant(), claims.getExpiration().toInstant()))
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5).plusSeconds(1));
    }

    @Test
    @DisplayName("시크릿이 틀리거나 등록되지 않은 클라이언트는 401이고, 둘을 구분할 수 없다")
    void issueSystemToken_rejected() throws Exception {
        String wrongSecret = mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest(CLIENT_ID, "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        String unknownClient = mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest("nobody", CLIENT_SECRET)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownClient).isEqualTo(wrongSecret);
    }

    @Test
    @DisplayName("시크릿 대입이 이어지면 잠겨서, 맞는 시크릿으로도 발급받지 못한다")
    void issueSystemToken_lockedAfterFailures() throws Exception {
        String lockedClient = "locked-client"; // 다른 테스트의 클라이언트를 잠그지 않도록 별도 ID로 시도한다
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/internal/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tokenRequest(lockedClient, "guess-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        // 이 클라이언트 ID는 이제 잠겼다. 어떤 시크릿을 보내도 비교 자체를 하지 않는다.
        mockMvc.perform(post("/internal/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest(lockedClient, "anything")))
                .andExpect(status().isUnauthorized());
    }

    // ---- RS256과 JWKS ----

    @Test
    @DisplayName("토큰은 RS256으로 서명되고, JWKS의 공개키만으로 서명을 검증할 수 있다")
    void tokensVerifiableWithJwks() throws Exception {
        MvcResult jwksResult = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                // 개인키 성분은 절대 나가면 안 된다
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andReturn();
        JsonNode jwk = json(jwksResult).at("/keys/0");
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asString())),
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asString()))));

        // 고객 토큰과 시스템 토큰 모두, ibank의 다른 도움 없이 공개키만으로 검증된다 (다른 서비스가 하는 방식)
        String userToken = userService.login(new LoginRequest("sysauthuser", "password123")).accessToken();
        String systemToken = jwtProvider.generateSystemToken(CLIENT_ID, Duration.ofMinutes(5));
        for (String token : new String[]{userToken, systemToken}) {
            Jws<Claims> verified = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
            assertThat(verified.getHeader().getAlgorithm()).isEqualTo("RS256");
            assertThat(verified.getHeader().getKeyId()).isEqualTo(jwk.get("kid").asString());
        }
        assertThat(jwk.get("kid").asString()).isEqualTo(jwtKeys.signingKeyId());
    }

    @Test
    @DisplayName("공개키를 HMAC 비밀키처럼 써서 만든 HS256 토큰은 거부된다 (알고리즘 혼동 공격)")
    void hs256TokenSignedWithPublicKey_rejected() throws Exception {
        // 공격자는 공개키를 안다. 검증 쪽이 헤더의 alg를 믿고 같은 키로 HMAC 검증을 하면 위조가 통한다.
        byte[] publicKeyBytes = jwtKeys.verificationKey(jwtKeys.signingKeyId()).getEncoded();
        String forged = Jwts.builder()
                .header().keyId(jwtKeys.signingKeyId()).and()
                .subject("sysauthuser")
                .claim("typ", "user").claim("role", "ROLE_USER").claim("tv", 0)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(publicKeyBytes), Jwts.SIG.HS256)
                .compact();
        String forgedSystem = Jwts.builder()
                .header().keyId(jwtKeys.signingKeyId()).and()
                .subject(CLIENT_ID)
                .claim("typ", "system").claim("role", "ROLE_SYSTEM")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(publicKeyBytes), Jwts.SIG.HS256)
                .compact();

        assertThat(jwtProvider.parse(forged)).isNull();
        assertThat(jwtProvider.parse(forgedSystem)).isNull();
        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/transactions/1").header("Authorization", "Bearer " + forgedSystem))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("모르는 kid의 토큰과 서명이 변조된 토큰은 거부된다")
    void unknownKidOrTamperedToken_rejected() {
        String token = jwtProvider.generateSystemToken(CLIENT_ID, Duration.ofMinutes(5));
        String[] parts = token.split("\\.");

        // 페이로드의 subject를 바꾼다 → 서명 불일치
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace(CLIENT_ID, "evil-service").getBytes());
        assertThat(jwtProvider.parse(parts[0] + "." + tamperedPayload + "." + parts[2])).isNull();

        // 헤더의 kid를 바꾼다 → 검증 키를 찾지 못한다
        String otherKidHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[0]))
                        .replace(jwtKeys.signingKeyId(), "unknown-kid-0000000000").getBytes());
        assertThat(jwtProvider.parse(otherKidHeader + "." + parts[1] + "." + parts[2])).isNull();

        assertThat(jwtProvider.parse(token)).isNotNull();
    }

    // ---- 토큰 종류별 접근 범위 ----

    @Test
    @DisplayName("내부 API는 토큰이 없으면 401, 고객 토큰이면 403")
    void internalApi_requiresSystemToken() throws Exception {
        mockMvc.perform(get("/internal/transactions/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String userToken = userService.login(new LoginRequest("sysauthuser", "password123")).accessToken();
        mockMvc.perform(get("/internal/transactions/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("시스템 토큰으로는 고객 API에 들어갈 수 없다 (403)")
    void systemToken_cannotUseCustomerApi() throws Exception {
        String systemToken = jwtProvider.generateSystemToken(CLIENT_ID, Duration.ofMinutes(5));

        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + systemToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/transactions/transfer").header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/fraud-alerts").header("Authorization", "Bearer " + systemToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("만료된 시스템 토큰은 거부된다")
    void expiredSystemToken_rejected() throws Exception {
        String expired = jwtProvider.generateSystemToken(CLIENT_ID, Duration.ofSeconds(-5));

        mockMvc.perform(get("/internal/transactions/1").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("고객 API의 오류 응답에는 code 필드가 생기지 않는다 (기존 응답 모양 유지)")
    void customerApiErrors_haveNoCodeField() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"sysauthuser\",\"password\":\"wrong-password\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // ---- 헬퍼 ----

    private String tokenRequest(String clientId, String clientSecret) {
        return "{\"clientId\":\"%s\",\"clientSecret\":\"%s\"}".formatted(clientId, clientSecret);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
