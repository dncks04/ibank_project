package com.ibank.bulkload;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 적재 한 번의 집계(스텝별 적재·이미 있음·건너뜀 건수). 끝난 뒤 요약 출력에 쓴다.
 * 메모리에만 있으므로 실행마다 {@link #reset()}으로 비운다. 건너뛴 행의 상세는 {@code bulk_load_errors}에 있다.
 */
@Component
@Profile("bulk-load")
public class BulkLoadStats {

    /** 스텝 하나의 집계. */
    public record StepCounts(long loaded, long alreadyLoaded, long errors) {}

    private final Map<String, AtomicLong> loaded = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> alreadyLoaded = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();

    public void reset() {
        loaded.clear();
        alreadyLoaded.clear();
        errors.clear();
    }

    public void count(String step, BulkLoadApplier.Result result) {
        Map<String, AtomicLong> target = result == BulkLoadApplier.Result.LOADED ? loaded : alreadyLoaded;
        target.computeIfAbsent(step, k -> new AtomicLong()).incrementAndGet();
    }

    public void countError(String step) {
        errors.computeIfAbsent(step, k -> new AtomicLong()).incrementAndGet();
    }

    public StepCounts of(String step) {
        return new StepCounts(get(loaded, step), get(alreadyLoaded, step), get(errors, step));
    }

    private static long get(Map<String, AtomicLong> map, String step) {
        AtomicLong value = map.get(step);
        return value == null ? 0 : value.get();
    }
}
