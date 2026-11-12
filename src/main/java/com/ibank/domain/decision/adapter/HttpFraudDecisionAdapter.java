package com.ibank.domain.decision.adapter;

import com.ibank.domain.decision.FraudDecisionPort;
import com.ibank.domain.decision.FraudDecisionUnavailableException;
import com.ibank.global.config.FdsProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FDS 서비스의 {@code POST /fds/evaluate}를 호출하는 판정 어댑터. 계약은 {@code docs/contracts/fds-evaluate.openapi.yaml}.
 *
 * <p>FDS 서비스가 느리거나 죽어도 거래 처리가 끌려가지 않도록 두 겹으로 막는다.
 * <ol>
 *   <li><b>HTTP 클라이언트 타임아웃</b>: 연결·응답 타임아웃을 클라이언트 자체에 건다. 서킷 브레이커와 무관하게
 *       호출 한 번이 기다리는 시간의 상한이다.</li>
 *   <li><b>서킷 브레이커</b>: 최근 호출의 실패율이 임계치를 넘으면 회로를 열어, 한동안 호출을 생략하고 바로 실패시킨다.
 *       FDS 서비스가 죽었을 때 모든 거래가 타임아웃만큼 기다리는 것을 막는다.</li>
 * </ol>
 *
 * <p>판정을 받지 못한 모든 경우(타임아웃, 연결 실패, 2xx 외 응답, 해석할 수 없는 응답, 회로 열림)는
 * {@link FraudDecisionUnavailableException}으로 바꿔 던진다. 호출자는 대체 정책으로 판정한다.
 *
 * <p>요청·응답 본문은 Map으로 직접 만들고 읽는다. 금액은 십진 문자열, 시각은 오프셋 포함 ISO-8601이라는
 * 계약 형식이 JSON 라이브러리의 기본 직렬화 설정에 좌우되지 않게 하기 위함이다.
 */
@Slf4j
public class HttpFraudDecisionAdapter implements FraudDecisionPort {

    private static final String PATH = "/fds/evaluate";
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z0-9_]{1,50}");
    private static final int MAX_REASON_CODES = 20;
    private static final int MAX_DECISION_ID_LENGTH = 64;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public HttpFraudDecisionAdapter(FdsProperties properties) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("ibank.fds.mode=http 이면 ibank.fds.base-url이 필요합니다.");
        }
        // 연결 타임아웃은 HttpClient에, 응답 타임아웃은 요청 팩토리(요청별 timeout)에 건다
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);
        if (properties.apiToken() != null && !properties.apiToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken());
        }
        this.restClient = builder.build();

        FdsProperties.CircuitBreaker cb = properties.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of("fds-evaluate", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumCalls())
                .failureRateThreshold(cb.failureRateThreshold())
                .waitDurationInOpenState(cb.openDuration())
                .permittedNumberOfCallsInHalfOpenState(cb.halfOpenCalls())
                // 대기 시간이 지나면 다음 호출을 기다리지 않고 스스로 반열림으로 넘어간다
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("FDS 판정 서킷 브레이커 상태 변경: {}", event.getStateTransition()));
    }

    /** 현재 회로 상태. 테스트와 모니터링용. */
    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    @Override
    public Decision evaluate(EvaluationRequest request) {
        try {
            // 호출 실패와 잘못된 응답을 모두 실패로 집계한다
            return circuitBreaker.executeSupplier(() -> call(request));
        } catch (CallNotPermittedException e) {
            throw new FraudDecisionUnavailableException("FDS 판정 서킷 브레이커가 열려 있어 호출을 생략했습니다.", e);
        }
    }

    private Decision call(EvaluationRequest request) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(toBody(request))
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃(ResourceAccessException), 2xx 외 응답, 본문 파싱 실패가 모두 여기로 온다
            throw new FraudDecisionUnavailableException("FDS 판정 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        return toDecision(body);
    }

    private static Map<String, Object> toBody(EvaluationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", request.transactionId());
        body.put("idempotencyKey", request.idempotencyKey());
        body.put("type", request.type().name());
        body.put("channel", request.channel().name());
        body.put("fromAccountNumber", request.fromAccountNumber());
        body.put("toAccountNumber", request.toAccountNumber()); // 출금이면 null
        body.put("amount", request.amount().toPlainString());
        body.put("customerId", request.customerId());
        body.put("requestedAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(request.requestedAt()));
        return body;
    }

    /**
     * 응답을 판정으로 바꾼다. {@code decision}만은 엄격하게 본다 — APPROVE/HOLD가 아니면 판정을 받지 못한 것이다.
     * 나머지 필드는 형식이 어긋나면 그 필드만 버린다. 유효한 판정을 부가 정보의 형식 문제로 통째로 버리면,
     * FDS 서비스가 보류하라고 한 거래가 대체 정책에 의해 승인될 수 있기 때문이다.
     */
    private static Decision toDecision(Map<String, Object> body) {
        if (body == null) {
            throw new FraudDecisionUnavailableException("FDS 판정 응답 본문이 비어 있습니다.");
        }
        Verdict verdict = parseEnum(Verdict.class, body.get("decision"));
        if (verdict == null) {
            throw new FraudDecisionUnavailableException("FDS 판정 응답의 decision 값을 해석할 수 없습니다.");
        }

        RiskLevel riskLevel = parseEnum(RiskLevel.class, body.get("riskLevel"));
        if (riskLevel == null && body.get("riskLevel") != null) {
            log.warn("FDS 판정 응답의 riskLevel을 해석할 수 없어 무시합니다.");
        }

        String decisionId = body.get("decisionId") instanceof String s && !s.isBlank() ? s : null;
        if (decisionId != null && decisionId.length() > MAX_DECISION_ID_LENGTH) {
            log.warn("FDS 판정 응답의 decisionId가 {}자를 넘어 무시합니다.", MAX_DECISION_ID_LENGTH);
            decisionId = null;
        }

        return new Decision(verdict, decisionId, riskLevel, parseReasonCodes(body.get("reasonCodes")), Source.FDS);
    }

    private static List<String> parseReasonCodes(Object raw) {
        List<String> codes = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return codes;
        }
        int dropped = 0;
        for (Object item : list) {
            if (item instanceof String code && REASON_CODE.matcher(code).matches() && codes.size() < MAX_REASON_CODES) {
                codes.add(code);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.warn("FDS 판정 응답의 reasonCodes 중 형식에 맞지 않거나 개수를 넘는 {}개를 버렸습니다.", dropped);
        }
        return codes;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object raw) {
        if (!(raw instanceof String name)) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
