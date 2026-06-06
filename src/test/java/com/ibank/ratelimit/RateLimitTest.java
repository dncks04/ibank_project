package com.ibank.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일반 API 호출 제한(rate limit) 검증.
 *
 * enabled=true + 낮은 용량(capacity=3)으로 띄워, 용량을 넘긴 요청이 429와
 * Retry-After로 거부되는지 확인한다. (기본 프로파일은 비활성이라 다른 테스트에는 영향 없음)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "ibank.rate-limit.enabled=true",
        "ibank.rate-limit.capacity=3",
        "ibank.rate-limit.refill-tokens=3",
        "ibank.rate-limit.refill-period=1m"
})
class RateLimitTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    WebApplicationContext wac;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("용량을 넘긴 호출은 429와 Retry-After로 거부된다")
    void exceedingCapacity_returns429() throws Exception {
        // capacity=3 → 앞 3건은 제한을 통과(미인증이라 401), 4번째부터 호출 제한에 걸려 429
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/accounts/me"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
