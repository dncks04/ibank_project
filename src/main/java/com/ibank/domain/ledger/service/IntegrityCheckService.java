package com.ibank.domain.ledger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정산 배치의 시스템 전체 검증. 계좌별 검증(잔액 == 원장 합계)으로는 잡히지 않는 것들을 본다.
 *
 * <ul>
 *   <li><b>원장</b>: 모든 분개의 합이 0인지, 전 원장 시산표가 0인지 — 돈이 생기거나 사라지지 않았는가.</li>
 *   <li><b>홀드</b>: 묶인 금액과 거래 상태가 서로 맞는지 —
 *     <ul>
 *       <li>계좌의 ACTIVE 홀드 합계가 원장잔액을 넘지 않는다(가용잔액이 음수가 아니다).</li>
 *       <li>ACTIVE 홀드는 판정 대기(PENDING_REVIEW)·보류(HELD) 거래에만 걸려 있다.</li>
 *       <li>판정 대기·보류 거래는 반드시 ACTIVE 홀드를 가진다.</li>
 *       <li>실행되지 않은 거래(취소·만료·실패)에는 분개가 없다.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>홀드 불변식은 DB 제약으로 걸 수 없고(여러 테이블에 걸친 조건) 코드의 락 규율로만 지켜지므로,
 * 규율이 깨진 버그를 여기서 사후에 잡는다.
 *
 * <p>각 검증은 SQL <b>한 문장</b>이다. 한 문장은 한 스냅샷에서 실행되므로, 검증 도중 거래가 커밋되어도
 * "홀드는 이미 풀렸는데 거래는 아직 보류"처럼 서로 다른 시점을 섞어 읽은 오탐이 나지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityCheckService {

    private static final int SAMPLE_SIZE = 20;

    /** 검증 한 항목의 결과. */
    public record CheckResult(String checkName, long violations, String sample) {
        public boolean consistent() {
            return violations == 0;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationService reconciliationService;

    /** 모든 항목을 검증한다. 위반이 있어도 예외를 던지지 않고 결과로 돌려준다. */
    @Transactional(readOnly = true)
    public List<CheckResult> runAll() {
        List<CheckResult> results = new ArrayList<>();

        List<String> unbalanced = reconciliationService.findUnbalancedJournals();
        results.add(new CheckResult("UNBALANCED_JOURNALS", unbalanced.size(), sampleOf(unbalanced)));

        BigDecimal trialBalance = reconciliationService.trialBalance();
        results.add(new CheckResult("TRIAL_BALANCE", trialBalance.signum() == 0 ? 0 : 1,
                trialBalance.signum() == 0 ? null : "시산표 합계 " + trialBalance.toPlainString()));

        results.add(idCheck("HOLD_SUM_EXCEEDS_BALANCE", """
                SELECT a.id FROM accounts a
                JOIN account_holds h ON h.account_id = a.id AND h.status = 'ACTIVE'
                GROUP BY a.id, a.balance
                HAVING SUM(h.amount) > a.balance
                ORDER BY a.id
                """));

        results.add(idCheck("ACTIVE_HOLD_WITHOUT_OPEN_TRANSACTION", """
                SELECT h.id FROM account_holds h
                JOIN transactions t ON t.id = h.transaction_id
                WHERE h.status = 'ACTIVE' AND t.status NOT IN ('PENDING_REVIEW', 'HELD')
                ORDER BY h.id
                """));

        results.add(idCheck("OPEN_TRANSACTION_WITHOUT_ACTIVE_HOLD", """
                SELECT t.id FROM transactions t
                WHERE t.status IN ('PENDING_REVIEW', 'HELD')
                  AND NOT EXISTS (SELECT 1 FROM account_holds h
                                  WHERE h.transaction_id = t.id AND h.status = 'ACTIVE')
                ORDER BY t.id
                """));

        // 지시서의 "CANCELLED / EXPIRED 거래에는 분개가 없음"에 FAILED를 더했다 — 셋 다 자금이 이동하지 않은 종료 상태다
        results.add(idCheck("UNEXECUTED_TRANSACTION_WITH_LEDGER", """
                SELECT DISTINCT t.id FROM transactions t
                JOIN ledger_entries l ON l.transaction_id = t.id
                WHERE t.status IN ('CANCELLED', 'EXPIRED', 'FAILED')
                ORDER BY t.id
                """));

        results.stream().filter(r -> !r.consistent()).forEach(r ->
                log.warn("정산 검증 위반: {} {}건 (예: {})", r.checkName(), r.violations(), r.sample()));
        return results;
    }

    /**
     * 검증 결과를 저장한다. {@code REQUIRES_NEW}: 위반이 있으면 정산 잡은 예외로 실패하는데, 그때 스텝의
     * 트랜잭션이 롤백되어도 "무엇이 위반이었는지"는 남아야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long jobExecutionId, List<CheckResult> results) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (CheckResult result : results) {
            jdbcTemplate.update("""
                    INSERT INTO reconciliation_check_results
                        (job_execution_id, check_name, violations, consistent, sample, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, jobExecutionId, result.checkName(), result.violations(), result.consistent(),
                    result.sample(), now);
        }
    }

    /** 위반 대상의 ID를 돌려주는 쿼리로 검증한다. 건수는 전체, 예시는 앞의 일부만 남긴다. */
    private CheckResult idCheck(String name, String sql) {
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class);
        return new CheckResult(name, ids.size(), sampleOf(ids));
    }

    private static String sampleOf(List<?> items) {
        if (items.isEmpty()) {
            return null;
        }
        String joined = items.stream().limit(SAMPLE_SIZE).map(String::valueOf).collect(Collectors.joining(","));
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }
}
