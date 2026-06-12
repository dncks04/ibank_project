package com.ibank.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 도메인 커스텀 메트릭의 단일 진입점.
 *
 * <p>HTTP 지연/처리율은 actuator의 {@code http.server.requests}가 자동 수집하므로,
 * 여기서는 비즈니스 관점 카운터만 다룬다:
 * <ul>
 *   <li>{@code ibank.operations} — 감사 대상 금융 행위의 성공/실패 (action·result 태그)</li>
 *   <li>{@code ibank.idempotency.replays} — 멱등성 키 중복 적중(저장된 결과 재반환)</li>
 *   <li>{@code ibank.ratelimit.rejections} — 호출 제한 429 거부</li>
 * </ul>
 *
 * <p>태그 값은 코드가 정한 유한 집합(action enum 등)만 사용한다 — 사용자 입력을 태그로
 * 쓰면 메트릭 카디널리티가 폭발한다.
 */
@Component
public class IbankMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> operationCounters = new ConcurrentHashMap<>();

    public IbankMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 감사 대상 행위 결과 카운트. action=TRANSFER/DEPOSIT/..., result=SUCCESS/FAILURE */
    public void countOperation(String action, String result) {
        operationCounters.computeIfAbsent(action + "|" + result, key ->
                Counter.builder("ibank.operations")
                        .description("감사 대상 금융 행위의 성공/실패 건수")
                        .tag("action", action)
                        .tag("result", result)
                        .register(registry)
        ).increment();
    }

    /** 멱등성 키 중복 적중(이미 처리된 거래의 결과 재반환). */
    public void countIdempotentReplay() {
        registry.counter("ibank.idempotency.replays").increment();
    }

    /** 호출 제한 초과로 거부된 요청(429). */
    public void countRateLimitRejection() {
        registry.counter("ibank.ratelimit.rejections").increment();
    }
}
