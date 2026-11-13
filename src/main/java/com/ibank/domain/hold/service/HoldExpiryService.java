package com.ibank.domain.hold.service;

import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * 홀드 만료 안전장치: 최대 보류 시간({@code ibank.holds.max-duration}, 기본 24시간)을 넘긴 홀드를 풀어 준다.
 *
 * <p>보류된 거래를 실행할지 취소할지는 FDS 서비스가 정한다(보호자 무응답 시 처리 등 실제 만료 정책 포함).
 * ibank는 그 결정을 기다리되, FDS 서비스가 사라지거나 결정을 잊어도 <b>고객의 돈이 영원히 묶여 있지는 않게</b>
 * 상한만 강제한다. 만료된 거래는 실행되지 않는다 — 돈은 움직이지 않고 가용잔액과 1일 한도 사용량만 돌아온다.
 *
 * <p>한 건이 한 트랜잭션이다({@link HoldExpiryProcessor}). 한 건의 실패가 나머지를 막지 않고,
 * 쓰기 트랜잭션이 짧아 Outbox 이벤트 공개를 지연시키지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldExpiryService {

    private final JdbcTemplate jdbcTemplate;
    private final HoldExpiryProcessor processor;
    private final BusinessClock businessClock;

    @Value("${ibank.holds.expiry.batch-size:200}")
    private int batchSize;

    /**
     * 만료 시각이 지난 ACTIVE 홀드를 오래된 순으로 처리한다. 트랜잭션 없이 호출한다.
     *
     * @return 이번 실행에서 풀린 홀드 수
     */
    public int expireOverdue() {
        // 부분 인덱스 (expires_at) WHERE status = 'ACTIVE'를 탄다
        List<Long> transactionIds = jdbcTemplate.queryForList("""
                SELECT transaction_id FROM account_holds
                WHERE status = 'ACTIVE' AND expires_at <= ?
                ORDER BY expires_at
                LIMIT ?
                """, Long.class, Timestamp.valueOf(businessClock.nowForStorage()), batchSize);

        int expired = 0;
        for (Long transactionId : transactionIds) {
            try {
                if (processor.expireIfOverdue(transactionId)) {
                    expired++;
                }
            } catch (Exception e) {
                // 락 대기 시간 초과 등. 이 건은 다음 실행에서 다시 시도된다.
                log.warn("홀드 만료 처리 실패 (transactionId={}) — 다음 실행에서 재시도: {}", transactionId, e.toString());
            }
        }
        if (!transactionIds.isEmpty()) {
            log.info("홀드 만료: 대상 {}건 중 {}건 해제", transactionIds.size(), expired);
        }
        return expired;
    }
}
