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
        attempts.compute(loginId, (key, existing) -> {
            Attempt attempt = (existing == null) ? new Attempt() : existing;
            attempt.count++;
            if (attempt.count >= MAX_ATTEMPTS) {
                attempt.lockedUntil = Instant.now().plus(LOCK_DURATION);
            }
            return attempt;
        });
    }

    /** 로그인 성공 시 카운터 초기화. */
    public void reset(String loginId) {
        attempts.remove(loginId);
    }

    private static final class Attempt {
        private int count;
        private Instant lockedUntil;
    }
}
