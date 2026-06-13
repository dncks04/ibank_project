package com.ibank.fraud;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.fraud.service.FraudDetectionService;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.config.FraudDetectionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심야 고액 룰의 구간 판정 단위 테스트. 시각을 주입할 수 있어 Docker 없이 검증한다.
 * 핵심: 자정을 넘는 심야 구간(예: 23~06시)이 올바르게 발동하는지.
 */
class FraudNightWindowTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    /** 심야 고액 룰만 분리 검증: velocity·신규수취인 룰은 사실상 끈다. */
    private FraudDetectionService serviceWithNightWindow(int startHour, int endHour) {
        FraudDetectionProperties props = new FraudDetectionProperties(
                true,                              // enabled
                5,                                 // velocityWindowMinutes
                1000,                              // velocityMaxOutgoing (사실상 비활성)
                new BigDecimal("5000000"),         // nightHighValueMin
                startHour,
                endHour,
                new BigDecimal("999999999"));      // newPayeeHighValueMin (사실상 비활성)
        when(transactionRepository.countOutgoingSince(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        return new FraudDetectionService(props, transactionRepository);
    }

    private List<String> evaluateAt(FraudDetectionService service, int hour) {
        Account from = mock(Account.class);
        Account to = mock(Account.class);
        when(from.getId()).thenReturn(1L);
        return service.evaluate(from, to, new BigDecimal("6000000"),
                LocalDateTime.of(2026, 6, 13, hour, 0));
    }

    @ParameterizedTest(name = "{0}시 고액 이체는 심야 구간(23~06)에서 보류 신호")
    @ValueSource(ints = {23, 0, 2, 5})
    @DisplayName("자정 횡단 심야 구간(23~06)은 자정 전후 모두 발동한다")
    void nightWindow_crossingMidnight_triggers(int hour) {
        FraudDetectionService service = serviceWithNightWindow(23, 6);
        assertThat(evaluateAt(service, hour))
                .contains(FraudDetectionService.RULE_NIGHT_HIGH_VALUE);
    }

    @ParameterizedTest(name = "{0}시 고액 이체는 주간이라 발동하지 않는다")
    @ValueSource(ints = {6, 12, 18, 22})
    @DisplayName("자정 횡단 심야 구간(23~06) 바깥(주간)에서는 발동하지 않는다")
    void dayTime_crossingMidnightConfig_doesNotTrigger(int hour) {
        FraudDetectionService service = serviceWithNightWindow(23, 6);
        assertThat(evaluateAt(service, hour))
                .doesNotContain(FraudDetectionService.RULE_NIGHT_HIGH_VALUE);
    }

    @Test
    @DisplayName("같은 날 구간(0~5)도 기존대로 발동한다 (회귀 방지)")
    void sameDayWindow_stillWorks() {
        FraudDetectionService service = serviceWithNightWindow(0, 5);
        assertThat(evaluateAt(service, 2))
                .contains(FraudDetectionService.RULE_NIGHT_HIGH_VALUE);
        assertThat(evaluateAt(service, 5))
                .doesNotContain(FraudDetectionService.RULE_NIGHT_HIGH_VALUE);
    }

    @Test
    @DisplayName("start == end는 빈 구간으로 보아 발동하지 않는다")
    void emptyWindow_neverTriggers() {
        FraudDetectionService service = serviceWithNightWindow(3, 3);
        assertThat(evaluateAt(service, 3))
                .doesNotContain(FraudDetectionService.RULE_NIGHT_HIGH_VALUE);
    }
}
