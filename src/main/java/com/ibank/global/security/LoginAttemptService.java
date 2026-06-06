package com.ibank.global.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 실패 누적 기반 brute-force 방어.
 *
 * 단일 인스턴스 메모리 기반(ConcurrentHashMap). 다중 인스턴스 환경에서는
 * Redis 등 공유 저장소로 대체해야 한다 (후속 과제).
 */
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    // 메모리 무한증가(서로 다른 loginId 대량 주입 DoS) 방어:
    // 항목 수가 임계치를 넘으면 잠금 상태가 아닌 오래된 항목을 스윕한다.
    static final int CLEANUP_THRESHOLD = 10_000;
    static final Duration ENTRY_TTL = LOCK_DURATION;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 잠금 상태면 true. 만료된 잠금은 자동 해제한다. */
    public boolean isLocked(String loginId) {
        Attempt attempt = attempts.get(loginId);
        if (attempt == null || attempt.lockedUntil == null) {
            return false;
        }
        if (attempt.lockedUntil.isAfter(Instant.now())) {
            return true;
        }
        attempts.remove(loginId);
        return false;
    }

    /** 로그인 실패 기록. 임계치 도달 시 잠금. */
    public void recordFailure(String loginId) {
        Instant now = Instant.now();
        attempts.compute(loginId, (key, existing) -> {
            Attempt attempt = (existing == null) ? new Attempt() : existing;
            attempt.count++;
            attempt.lastUpdated = now;
            if (attempt.count >= MAX_ATTEMPTS) {
                attempt.lockedUntil = now.plus(LOCK_DURATION);
            }
            return attempt;
        });
        if (attempts.size() > CLEANUP_THRESHOLD) {
            cleanupStale(now);
        }
    }

    /** 로그인 성공 시 카운터 초기화. */
    public void reset(String loginId) {
        attempts.remove(loginId);
    }

    /** 잠금 중이 아니면서 TTL이 지난 항목 제거. 활성 잠금은 보존한다. */
    private void cleanupStale(Instant now) {
        attempts.values().removeIf(a ->
                (a.lockedUntil == null || !a.lockedUntil.isAfter(now))
                        && a.lastUpdated != null
                        && a.lastUpdated.isBefore(now.minus(ENTRY_TTL)));
    }

    private static final class Attempt {
        private int count;
        private Instant lockedUntil;
        private Instant lastUpdated;
    }
}
