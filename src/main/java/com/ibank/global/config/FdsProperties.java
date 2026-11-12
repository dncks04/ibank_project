package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * FDS 판정 서비스 연동 설정.
 *
 * @param mode            판정 방식. {@code always-approve}(기본, 로컬 개발·테스트) 또는 {@code http}(FDS 서비스 호출).
 * @param baseUrl         FDS 서비스 주소. {@code http} 모드에서 필수.
 * @param apiToken        FDS 서비스 호출 시 Bearer로 보내는 토큰. 비어 있으면 헤더를 붙이지 않는다.
 * @param connectTimeout  연결 타임아웃.
 * @param responseTimeout 응답 타임아웃(요청을 보낸 뒤 응답을 받기까지).
 */
@ConfigurationProperties(prefix = "ibank.fds")
public record FdsProperties(
        @DefaultValue("always-approve") String mode,
        String baseUrl,
        String apiToken,
        @DefaultValue("300ms") Duration connectTimeout,
        @DefaultValue("300ms") Duration responseTimeout,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Fallback fallback,
        @DefaultValue Recovery recovery
) {

    /**
     * 서킷 브레이커. 최근 호출의 실패율이 임계치를 넘으면 회로를 열어, 일정 시간 동안 호출 자체를 생략하고
     * 바로 대체 정책으로 넘어간다. FDS 서비스가 죽었을 때 모든 거래가 타임아웃만큼 기다리는 것을 막는다.
     *
     * @param failureRateThreshold 회로를 여는 실패율(%).
     * @param slidingWindowSize    실패율을 계산하는 최근 호출 수.
     * @param minimumCalls         실패율을 계산하기 시작하는 최소 호출 수.
     * @param openDuration         회로를 열어 두는 시간. 지나면 시험 호출을 흘려 복구 여부를 확인한다.
     * @param halfOpenCalls        복구 확인에 쓰는 시험 호출 수.
     */
    public record CircuitBreaker(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("10s") Duration openDuration,
            @DefaultValue("3") int halfOpenCalls
    ) {}

    /**
     * 판정을 받지 못했을 때의 대체 정책.
     *
     * @param elderAge 이 나이 이상(만 나이)이거나 나이를 알 수 없는 고객의 거래는 보류한다. 그 외는 승인한다.
     */
    public record Fallback(
            @DefaultValue("65") int elderAge
    ) {}

    /**
     * 판정 도중 처리가 끊겨 PENDING_REVIEW로 남은 거래의 복구.
     *
     * @param enabled    복구 스케줄러 사용 여부.
     * @param staleAfter 가승인 뒤 이 시간이 지나도록 판정이 반영되지 않은 거래를 복구 대상으로 본다.
     *                   정상 처리 시간(타임아웃 + 재시도)보다 충분히 길어야 처리 중인 거래를 건드리지 않는다.
     * @param batchSize  한 번에 복구할 최대 건수.
     */
    public record Recovery(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("1m") Duration staleAfter,
            @DefaultValue("100") int batchSize
    ) {}
}
