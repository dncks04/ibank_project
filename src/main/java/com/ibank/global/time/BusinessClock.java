package com.ibank.global.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;

/**
 * 업무 날짜 경계용 시계.
 *
 * <p>"오늘"이 언제 시작하고 끝나는지는 서버가 어느 타임존에서 도는지가 아니라 <b>영업 타임존</b>
 * (설정값 {@code ibank.business.zone}, 기본 Asia/Seoul)으로 정한다. 컨테이너는 보통 UTC로 돌기 때문에
 * JVM 기본 타임존에 기대면 1일 한도의 날짜 경계가 한국 시간 09시로 밀린다.
 *
 * <p>JVM 기본 타임존 자체는 바꾸지 않는다. DB에 저장된 시각({@code created_at} 등)은 지금까지
 * JVM 기본 타임존의 {@link LocalDateTime}으로 기록돼 왔으므로, 기본 타임존을 바꾸면 기존 데이터의
 * 해석이 달라진다. 대신 날짜 경계가 필요한 곳에서만 이 클래스로 경계를 계산하고, 그 경계를
 * <b>저장 타임존(= JVM 기본 타임존)의 LocalDateTime으로 변환</b>해 저장된 시각과 비교한다.
 */
public class BusinessClock {

    private final Clock clock;
    private final ZoneId businessZone;
    private final ZoneId storageZone;

    public BusinessClock(Clock clock, ZoneId businessZone, ZoneId storageZone) {
        this.clock = clock;
        this.businessZone = businessZone;
        this.storageZone = storageZone;
    }

    public ZoneId businessZone() {
        return businessZone;
    }

    /** 영업 타임존 기준 오늘 날짜. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    /** 저장 타임존 기준 현재 시각. 엔티티의 {@code LocalDateTime.now()}와 같은 기준이다. */
    public LocalDateTime nowForStorage() {
        return LocalDateTime.now(clock.withZone(storageZone));
    }

    /**
     * 영업일 하루의 구간 [시작, 끝)을 저장 타임존의 LocalDateTime으로 돌려준다.
     * 저장된 시각 컬럼과 {@code >= start AND < end}로 비교하는 용도.
     */
    public DayRange dayRange(LocalDate businessDate) {
        return new DayRange(
                toStorage(businessDate.atStartOfDay()),
                toStorage(businessDate.plusDays(1).atStartOfDay()));
    }

    /** 영업 타임존 기준 오늘의 구간. */
    public DayRange todayRange() {
        return dayRange(today());
    }

    /** 저장된 시각(저장 타임존의 LocalDateTime)을 영업 타임존 오프셋이 붙은 시각으로 바꾼다. 외부 응답·이벤트용. */
    public OffsetDateTime toBusinessOffset(LocalDateTime stored) {
        if (stored == null) {
            return null;
        }
        return stored.atZone(storageZone).withZoneSameInstant(businessZone).toOffsetDateTime();
    }

    /** 오프셋이 붙은 시각을 저장 타임존의 LocalDateTime으로 바꾼다. 외부에서 받은 조회 범위를 컬럼과 비교할 때 쓴다. */
    public LocalDateTime toStorage(OffsetDateTime instant) {
        if (instant == null) {
            return null;
        }
        return instant.atZoneSameInstant(storageZone).toLocalDateTime();
    }

    /** 영업 타임존 기준 오늘의 만 나이. 생년월일이 없으면 null. */
    public Integer ageOf(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        return Period.between(birthDate, today()).getYears();
    }

    private LocalDateTime toStorage(LocalDateTime businessLocal) {
        return businessLocal.atZone(businessZone).withZoneSameInstant(storageZone).toLocalDateTime();
    }

    /** 저장 타임존 기준의 반열린 구간 [start, end). */
    public record DayRange(LocalDateTime start, LocalDateTime end) {}
}
