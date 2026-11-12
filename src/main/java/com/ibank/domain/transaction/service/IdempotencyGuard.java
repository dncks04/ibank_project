package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.metrics.IbankMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 거래 멱등성 키 검증.
 *
 * <ul>
 *   <li>같은 키의 거래가 없으면 {@code Optional.empty()} — 정상 처리 진행.</li>
 *   <li>있고 요청 지문이 같으면 그 거래를 돌려준다(replay). 호출자는 현재 상태 그대로 응답한다.</li>
 *   <li>있는데 지문이 다르면 같은 키가 다른 내용에 재사용된 것이므로
 *       {@link IdempotencyKeyConflictException}(409). 옛 결과를 조용히 돌려주지 않는다.</li>
 *   <li>지문은 같은데 요청자가 그 거래의 계좌 주인이 아니면 {@link AccountAccessDeniedException}.
 *       replay는 새 거래를 만드는 경로의 소유권 검증보다 먼저 실행되므로, 여기서 확인하지 않으면
 *       키와 요청 내용을 아는 다른 사용자가 남의 거래 결과(상태·보류 사유)를 받아 갈 수 있다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final TransactionRepository transactionRepository;
    private final IbankMetrics metrics;

    public Optional<Transaction> findReplay(String idempotencyKey, String fingerprint, Long requesterUserId) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        existing.ifPresent(tx -> {
            if (!fingerprint.equals(tx.getRequestFingerprint())) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            // 출금·이체는 출금 계좌, 입금은 입금 계좌의 주인이 요청자여야 한다
            Account owned = tx.getFromAccount() != null ? tx.getFromAccount() : tx.getToAccount();
            if (!owned.getOwner().getId().equals(requesterUserId)) {
                throw new AccountAccessDeniedException(owned.getAccountNumber());
            }
            metrics.countIdempotentReplay();
        });
        return existing;
    }
}
