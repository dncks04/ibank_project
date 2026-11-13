package com.ibank.domain.hold.service;

import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.outbox.DomainEvents;
import com.ibank.domain.outbox.EventType;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.metrics.IbankMetrics;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 만료된 홀드 한 건의 처리. 거래 한 건 = 트랜잭션 하나.
 *
 * <p>락 순서는 다른 경로(확정·실행·취소)와 같다: 거래 행 → 계좌. 그래서 만료와 실행·취소가 같은 거래에
 * 동시에 들어와도 거래 행 락에서 줄을 서고, 먼저 들어온 쪽만 적용된다. 나중에 들어온 쪽은 락을 얻은 뒤
 * 상태를 다시 보고 물러난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryProcessor {

    /** 만료로 끝난 거래에 남기는 사유 코드. 이벤트의 reasonCode와 FAILED 거래의 failureCode로 쓰인다. */
    public static final String REASON_HOLD_EXPIRED = "HOLD_EXPIRED";

    private final TransactionRepository transactionRepository;
    private final AccountHoldRepository holdRepository;
    private final AccountRepository accountRepository;
    private final HoldService holdService;
    private final DomainEvents events;
    private final BusinessClock businessClock;
    private final IbankMetrics metrics;

    /**
     * 거래의 홀드가 만료 시각을 지났으면 풀고 거래를 종료 상태로 넘긴다.
     *
     * <ul>
     *   <li>HELD → EXPIRED. 보류된 채 아무도 실행·취소하지 않았다.</li>
     *   <li>PENDING_REVIEW → FAILED(HOLD_EXPIRED). 판정이 끝내 반영되지 않았다. 복구 배치가 먼저 처리하는 것이
     *       정상이므로, 여기까지 왔다면 복구가 꺼져 있거나 계속 실패한 것이다. 허용 전이표에 PENDING_REVIEW →
     *       EXPIRED가 없어 실패로 확정한다.</li>
     * </ul>
     *
     * @return 이번 호출이 홀드를 풀었으면 true. 이미 처리됐거나 아직 만료 전이면 false.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expireIfOverdue(Long transactionId) {
        // 거래 행 락. 이 트랜잭션에서 거래를 처음 읽는 것이 락 쿼리여야 최신 상태를 본다.
        Optional<Transaction> found = transactionRepository.findByIdForUpdate(transactionId);
        if (found.isEmpty()) {
            return false;
        }
        Transaction tx = found.get();
        AccountHold hold = holdRepository.findByTransactionId(transactionId).orElse(null);
        // 락을 기다리는 동안 실행·취소가 먼저 끝났으면 홀드는 더 이상 ACTIVE가 아니다
        if (hold == null || !hold.isActive() || hold.getExpiresAt().isAfter(businessClock.nowForStorage())) {
            return false;
        }

        // 홀드 해제는 계좌 락 안에서. 엔티티를 먼저 로딩하지 않고 번호만 읽어 잠근다.
        String accountNumber = accountRepository.findAccountNumberById(tx.getFromAccount().getId()).orElseThrow();
        accountRepository.findByAccountNumberWithLock(accountNumber).orElseThrow();

        holdService.expire(hold);
        if (tx.getStatus() == TransactionStatus.HELD) {
            tx.expire();
            events.transaction(EventType.TRANSACTION_EXPIRED, tx, hold, REASON_HOLD_EXPIRED);
            metrics.countOperation("HOLD_EXPIRE", "SUCCESS");
        } else if (tx.getStatus() == TransactionStatus.PENDING_REVIEW) {
            tx.fail(REASON_HOLD_EXPIRED);
            events.transaction(EventType.TRANSACTION_FAILED, tx, hold, REASON_HOLD_EXPIRED);
            metrics.countOperation("HOLD_EXPIRE_PENDING_REVIEW", "SUCCESS");
        } else {
            // 종료된 거래에 ACTIVE 홀드가 남아 있었다 — 있어서는 안 되는 상태다. 돈이 묶여 있지 않게 홀드는 풀되
            // 거래는 건드리지 않고 크게 알린다. 정산 배치의 홀드 검증도 같은 상태를 잡는다.
            log.error("종료된 거래에 ACTIVE 홀드가 남아 있어 만료로 해제함: transactionId={}, status={}",
                    transactionId, tx.getStatus());
            metrics.countOperation("HOLD_EXPIRE_ORPHAN", "FAILURE");
        }
        return true;
    }
}
