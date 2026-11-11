package com.ibank.global.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업 타임존과 서버(저장) 타임존이 다를 때 날짜 경계가 영업 타임존을 따르는지 검증한다.
 * 서버가 UTC, 영업 타임존이 Asia/Seoul인 컨테이너 배포 상황을 재현한다.
 */
class BusinessClockTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneOffset.UTC;

    private BusinessClock clockAt(String utcInstant, ZoneId storageZone) {
        return new BusinessClock(Clock.fixed(Instant.parse(utcInstant), UTC), SEOUL, storageZone);
    }

    @Test
    @DisplayName("UTC 15시(한국 자정)를 넘기면 영업일이 다음 날로 바뀐다")
    void today_followsBusinessZone() {
        assertThat(clockAt("2026-11-11T14:59:59Z", UTC).today()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(clockAt("2026-11-11T15:00:00Z", UTC).today()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("서버가 UTC면 영업일 구간은 전날 15시부터 당일 15시까지(UTC)다")
    void dayRange_convertedToStorageZone() {
        BusinessClock.DayRange range = clockAt("2026-11-11T03:00:00Z", UTC).todayRange();

        assertThat(range.start()).isEqualTo(LocalDateTime.of(2026, 11, 10, 15, 0));
        assertThat(range.end()).isEqualTo(LocalDateTime.of(2026, 11, 11, 15, 0));
    }

    @Test
    @DisplayName("서버가 영업 타임존과 같으면 구간은 자정부터 자정까지다")
    void dayRange_sameZone() {
        BusinessClock.DayRange range = clockAt("2026-11-11T03:00:00Z", SEOUL).todayRange();

        assertThat(range.start()).isEqualTo(LocalDateTime.of(2026, 11, 11, 0, 0));
        assertThat(range.end()).isEqualTo(LocalDateTime.of(2026, 11, 12, 0, 0));
    }

    @Test
    @DisplayName("한국 23:30 거래는 서버가 UTC여도 그날 구간에 속하고, 00:30 거래는 다음 날 구간에 속한다")
    void dayRange_classifiesTransactionsAroundMidnight() {
        BusinessClock clock = clockAt("2026-11-11T03:00:00Z", UTC);
        BusinessClock.DayRange nov11 = clock.dayRange(LocalDate.of(2026, 11, 11));

        LocalDateTime kst2330Stored = LocalDateTime.of(2026, 11, 11, 14, 30); // 한국 11일 23:30
        LocalDateTime kst0030Stored = LocalDateTime.of(2026, 11, 11, 15, 30); // 한국 12일 00:30

        assertThat(within(nov11, kst2330Stored)).isTrue();
        assertThat(within(nov11, kst0030Stored)).isFalse();
    }

    @Test
    @DisplayName("저장된 시각을 영업 타임존 오프셋 시각으로 바꾸고 되돌릴 수 있다")
    void offsetConversion_roundTrips() {
        BusinessClock clock = clockAt("2026-11-11T03:00:00Z", UTC);
        LocalDateTime stored = LocalDateTime.of(2026, 11, 11, 7, 0);

        OffsetDateTime business = clock.toBusinessOffset(stored);

        assertThat(business).isEqualTo(OffsetDateTime.of(2026, 11, 11, 16, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(clock.toStorage(business)).isEqualTo(stored);
    }

    @Test
    @DisplayName("만 나이는 영업 타임존의 오늘을 기준으로 생일 당일부터 올라간다")
    void ageOf_usesBusinessDate() {
        LocalDate birth = LocalDate.of(1961, 11, 12);

        // 한국 11일 → 아직 64세, 한국 12일(UTC로는 아직 11일 15시) → 65세
        assertThat(clockAt("2026-11-11T14:59:59Z", UTC).ageOf(birth)).isEqualTo(64);
        assertThat(clockAt("2026-11-11T15:00:00Z", UTC).ageOf(birth)).isEqualTo(65);
        assertThat(clockAt("2026-11-11T15:00:00Z", UTC).ageOf(null)).isNull();
    }

    private boolean within(BusinessClock.DayRange range, LocalDateTime t) {
        return !t.isBefore(range.start()) && t.isBefore(range.end());
    }
}
