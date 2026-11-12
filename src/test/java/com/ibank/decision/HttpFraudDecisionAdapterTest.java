package com.ibank.decision;

import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import com.ibank.domain.decision.FraudDecisionPort.RiskLevel;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.decision.FraudDecisionPort.Verdict;
import com.ibank.domain.decision.FraudDecisionUnavailableException;
import com.ibank.domain.decision.adapter.HttpFraudDecisionAdapter;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.global.config.FdsProperties;
import com.ibank.support.StubFdsServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FDS 판정 HTTP 어댑터. 실제 HTTP 서버(JDK 내장)를 상대로 계약 형식, 타임아웃, 서킷 브레이커를 검증한다.
 * DB가 필요 없으므로 Docker 없이도 돈다.
 */
class HttpFraudDecisionAdapterTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(300);

    private StubFdsServer fds;

    @BeforeEach
    void startServer() {
        fds = new StubFdsServer();
    }

    @AfterEach
    void stopServer() {
        fds.close();
    }

    // ---- 계약 형식 ----

    @Test
    @DisplayName("요청 본문은 계약 형식대로 나간다: 금액은 십진 문자열, 시각은 오프셋 포함 ISO-8601, Bearer 토큰")
    void request_followsContract() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), "secret-token");

        adapter.evaluate(transferRequest());

        assertThat(fds.received()).singleElement().satisfies(received -> {
            assertThat(received.authorization()).isEqualTo("Bearer secret-token");
            assertThat(received.body())
                    .contains("\"transactionId\":123")
                    .contains("\"idempotencyKey\":\"key-1\"")
                    .contains("\"type\":\"TRANSFER\"")
                    .contains("\"channel\":\"MOBILE\"")
                    .contains("\"fromAccountNumber\":\"20261111000123\"")
                    .contains("\"toAccountNumber\":\"20261101000456\"")
                    .contains("\"amount\":\"1500000.50\"") // 숫자가 아니라 문자열
                    .contains("\"customerId\":45")
                    .contains("\"requestedAt\":\"2026-11-11T16:00:00+09:00\"");
        });
    }

    @Test
    @DisplayName("출금이면 toAccountNumber는 null로 나가고, 토큰 설정이 없으면 Authorization 헤더를 붙이지 않는다")
    void request_withdrawalAndNoToken() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), null);

        adapter.evaluate(new EvaluationRequest(7L, "key-w", Transaction.TransactionType.WITHDRAWAL,
                Transaction.Channel.TELLER, "20261111000123", null, new BigDecimal("300000"), 45L,
                OffsetDateTime.of(2026, 11, 11, 16, 0, 0, 0, ZoneOffset.ofHours(9))));

        assertThat(fds.received()).singleElement().satisfies(received -> {
            assertThat(received.authorization()).isNull();
            assertThat(received.body())
                    .contains("\"type\":\"WITHDRAWAL\"")
                    .contains("\"channel\":\"TELLER\"")
                    .contains("\"toAccountNumber\":null")
                    .contains("\"amount\":\"300000\"");
        });
    }

    @Test
    @DisplayName("APPROVE와 HOLD 응답을 판정으로 읽는다")
    void response_parsed() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), null);

        Decision approve = adapter.evaluate(transferRequest());
        assertThat(approve.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(approve.riskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(approve.decisionId()).isEqualTo("dec-approve");
        assertThat(approve.reasonCodes()).isEmpty();
        assertThat(approve.source()).isEqualTo(Source.FDS);

        fds.respond(200, StubFdsServer.holdBody("NEW_PAYEE_HIGH_VALUE", "DEPOSIT_CLOSED_THEN_TRANSFER"));
        Decision hold = adapter.evaluate(transferRequest());
        assertThat(hold.verdict()).isEqualTo(Verdict.HOLD);
        assertThat(hold.riskLevel()).isEqualTo(RiskLevel.DANGER);
        assertThat(hold.reasonCodes()).containsExactly("NEW_PAYEE_HIGH_VALUE", "DEPOSIT_CLOSED_THEN_TRANSFER");
    }

    @Test
    @DisplayName("부가 필드의 형식이 어긋나도 판정 자체는 살린다 (잘못된 사유 코드·위험 등급·식별자만 버린다)")
    void response_lenientOnOptionalFields() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), null);
        // 쉼표가 든 코드는 저장 형식(쉼표 구분)을 깨뜨리므로 버려야 한다
        fds.respond(200, """
                {"decision":"HOLD","riskLevel":"EXTREME","reasonCodes":["GOOD_CODE","bad code","A,B",42],
                 "decisionId":"%s"}""".formatted("x".repeat(65)));

        Decision decision = adapter.evaluate(transferRequest());

        assertThat(decision.verdict()).isEqualTo(Verdict.HOLD);
        assertThat(decision.reasonCodes()).containsExactly("GOOD_CODE");
        assertThat(decision.riskLevel()).isNull();
        assertThat(decision.decisionId()).isNull();
    }

    // ---- 판정을 받지 못한 경우 ----

    @Test
    @DisplayName("응답이 응답 타임아웃보다 늦으면 기다리지 않고 판정 불가로 끝난다")
    void slowResponse_timesOut() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), null);
        fds.delay(2_000);

        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // 서버는 2초 뒤에 답하지만 호출은 응답 타임아웃(300ms) 근처에서 끝나야 한다
        assertThat(elapsed).isLessThan(Duration.ofMillis(1_500));
        assertThat(elapsed).isGreaterThanOrEqualTo(RESPONSE_TIMEOUT.minusMillis(50));
    }

    @Test
    @DisplayName("연결할 수 없으면 판정 불가")
    void connectionRefused() {
        String deadUrl = fds.baseUrl();
        fds.close(); // 포트를 닫아 연결 거부를 만든다
        HttpFraudDecisionAdapter adapter = adapter(deadUrl, null);

        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);
    }

    @Test
    @DisplayName("2xx가 아닌 응답, 깨진 본문, 알 수 없는 decision은 모두 판정 불가")
    void badResponses() {
        HttpFraudDecisionAdapter adapter = adapter(fds.baseUrl(), null);

        fds.respond(500, "{\"error\":\"boom\"}");
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);

        fds.respond(404, "{}");
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);

        fds.respond(200, "this is not json");
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);

        // 계약에 없는 판정(거절)은 받아들이지 않는다
        fds.respond(200, "{\"decision\":\"REJECT\",\"decisionId\":\"d\"}");
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);

        fds.respond(200, "{\"riskLevel\":\"NORMAL\"}");
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class);
    }

    // ---- 서킷 브레이커 ----

    @Test
    @DisplayName("실패가 이어지면 회로가 열려 호출을 생략하고, 대기 시간이 지나 FDS가 복구되면 다시 닫힌다")
    void circuitBreaker_opensThenRecovers() throws InterruptedException {
        // 최근 4건 중 절반 이상 실패하면 열리고, 500ms 뒤 시험 호출 2건으로 복구 여부를 확인한다
        HttpFraudDecisionAdapter adapter = new HttpFraudDecisionAdapter(new FdsProperties(
                "http", fds.baseUrl(), null, CONNECT_TIMEOUT, RESPONSE_TIMEOUT,
                new FdsProperties.CircuitBreaker(50, 4, 4, Duration.ofMillis(500), 2),
                new FdsProperties.Fallback(65),
                new FdsProperties.Recovery(false, Duration.ofMinutes(1), 100)));
        assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        fds.respond(500, "{}");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                    .isInstanceOf(FraudDecisionUnavailableException.class);
        }
        assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = fds.received().size();
        assertThat(callsWhenOpened).isEqualTo(4);

        // 열린 동안: FDS가 이미 복구됐더라도 호출 자체를 하지 않고 즉시 판정 불가로 끝난다
        fds.respond(200, StubFdsServer.approveBody());
        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.evaluate(transferRequest()))
                .isInstanceOf(FraudDecisionUnavailableException.class)
                .hasMessageContaining("서킷 브레이커");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
        assertThat(fds.received()).hasSize(callsWhenOpened);

        // 대기 시간이 지나면 시험 호출을 흘려보내고, 성공하면 회로가 닫힌다
        Thread.sleep(800);
        assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(adapter.evaluate(transferRequest()).verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(adapter.evaluate(transferRequest()).verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(adapter.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(fds.received()).hasSize(callsWhenOpened + 2);
    }

    @Test
    @DisplayName("base-url 없이 http 모드로 만들면 즉시 실패한다 (판정 없이 뜨는 일을 막는다)")
    void missingBaseUrl_failsFast() {
        assertThatThrownBy(() -> adapter(" ", null)).isInstanceOf(IllegalStateException.class);
    }

    // ---- 헬퍼 ----

    private HttpFraudDecisionAdapter adapter(String baseUrl, String token) {
        return new HttpFraudDecisionAdapter(new FdsProperties(
                "http", baseUrl, token, CONNECT_TIMEOUT, RESPONSE_TIMEOUT,
                // 이 테스트들에서는 회로가 열리지 않도록 창을 크게 둔다
                new FdsProperties.CircuitBreaker(50, 100, 100, Duration.ofSeconds(10), 3),
                new FdsProperties.Fallback(65),
                new FdsProperties.Recovery(false, Duration.ofMinutes(1), 100)));
    }

    private EvaluationRequest transferRequest() {
        return new EvaluationRequest(123L, "key-1", Transaction.TransactionType.TRANSFER,
                Transaction.Channel.MOBILE, "20261111000123", "20261101000456",
                new BigDecimal("1500000.50"), 45L,
                OffsetDateTime.of(2026, 11, 11, 16, 0, 0, 0, ZoneOffset.ofHours(9)));
    }
}
