package com.ibank.global.web;

import com.ibank.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 상관관계 ID 필터 검증. 응답에 항상 X-Request-Id가 실리고,
 * 유효한 클라이언트 ID는 이어받되 형식이 어긋난 값은 새로 발급하는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class CorrelationIdFilterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @Autowired CorrelationIdFilter correlationIdFilter;
    @MockitoBean UserService userService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        // MockMvc는 서블릿 컨테이너 필터를 자동 등록하지 않으므로 명시적으로 추가한다
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .addFilters(correlationIdFilter)
                .build();
    }

    @Test
    @DisplayName("X-Request-Id 없이 요청하면 서버가 발급해 응답 헤더로 돌려준다")
    void generatesIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"loginId\":\"u\",\"password\":\"p\"}"))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER))
                .isNotBlank();
    }

    @Test
    @DisplayName("유효한 클라이언트 X-Request-Id는 그대로 이어받는다")
    void propagatesValidClientId() throws Exception {
        String clientId = "client-req-12345678";

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header(CorrelationIdFilter.HEADER, clientId)
                        .contentType("application/json")
                        .content("{\"loginId\":\"u\",\"password\":\"p\"}"))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER))
                .isEqualTo(clientId);
    }

    @Test
    @DisplayName("형식이 어긋난 X-Request-Id는 무시하고 새로 발급한다")
    void replacesMalformedClientId() throws Exception {
        // CR/LF 인젝션은 서블릿 컨테이너/보안 방화벽이 요청 자체를 거부하므로,
        // 여기서는 그 방어를 통과하는 "형식 위반" 값(공백 포함)으로 필터의 검증을 확인한다
        String malformed = "bad id with spaces";

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header(CorrelationIdFilter.HEADER, malformed)
                        .contentType("application/json")
                        .content("{\"loginId\":\"u\",\"password\":\"p\"}"))
                .andReturn();

        String issued = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(issued).isNotBlank().isNotEqualTo(malformed);
    }
}
