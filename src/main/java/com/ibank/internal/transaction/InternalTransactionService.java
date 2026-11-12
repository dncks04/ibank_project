package com.ibank.internal.transaction;

import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.TransactionNotFoundException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.IdempotencyFingerprint;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.global.audit.Audited;
import com.ibank.global.time.BusinessClock;
import com.ibank.internal.idempotency.InternalIdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 보류 거래 내부 API의 처리. FDS·보호 흐름 서비스가 보류된 거래를 실행하거나 취소한다.
 *
 * <p>실행·취소는 한 트랜잭션 안에서 다음 순서로 진행한다.
 * <ol>
 *   <li><b>거래 행을 잠근다.</b> 같은 거래에 대한 모든 요청(실행, 취소, 같은 키의 재전송)이 여기서 줄을 선다.</li>
 *   <li><b>멱등성 키를 확인한다.</b> 같은 키·같은 요청이면 저장된 응답을 돌려주고 끝낸다. 락을 얻은 뒤에 보므로,
 *       같은 키의 요청이 동시에 와도 나중 것은 앞선 것이 저장한 응답을 본다.</li>
 *   <li>거래를 실행·취소한다({@link TransactionProcessor}). HELD가 아니면 409.</li>
 *   <li>키와 응답을 저장한다. 3의 변경과 함께 커밋된다.</li>
 * </ol>
 *
 * <p>실행이 재검증에 실패하면 예외가 전파되어 전체가 롤백된다. 거래는 HELD로 남고 키도 저장되지 않으므로,
 * 호출 서비스는 조건이 해소된 뒤 같은 키로 다시 시도할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class InternalTransactionService {

    private static final String OP_EXECUTE = "TRANSACTION_EXECUTE";
    private static final String OP_CANCEL = "TRANSACTION_CANCEL";

    private final TransactionRepository transactionRepository;
    private final AccountHoldRepository holdRepository;
    private final TransactionProcessor processor;
    private final InternalIdempotencyStore idempotencyStore;
    private final BusinessClock businessClock;

    /**
     * 거래와 홀드의 현재 상태. REPEATABLE_READ로 두 값을 같은 스냅샷에서 읽는다
     * (실행 도중에 읽어 "거래는 완료인데 홀드는 ACTIVE" 같은 어긋난 조합을 내보내지 않는다).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InternalTransactionResponse get(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return assemble(tx);
    }

    @Audited(action = "INTERNAL_TX_EXECUTE", target = "'transaction:' + #transactionId", detail = "#reasonCode")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InternalTransactionResponse execute(Long transactionId, String idempotencyKey, String reasonCode) {
        return resolve(OP_EXECUTE, transactionId, idempotencyKey, reasonCode, processor::executeHeld);
    }

    @Audited(action = "INTERNAL_TX_CANCEL", target = "'transaction:' + #transactionId", detail = "#reasonCode")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InternalTransactionResponse cancel(Long transactionId, String idempotencyKey, String reasonCode) {
        return resolve(OP_CANCEL, transactionId, idempotencyKey, reasonCode, processor::cancelHeld);
    }

    private InternalTransactionResponse resolve(String operation, Long transactionId, String idempotencyKey,
                                                String reasonCode, BiConsumer<Long, String> action) {
        // 1) 거래 행 락. 이 트랜잭션에서 거래를 처음 읽는 것이 락 쿼리여야 최신 상태를 본다.
        Transaction tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // 2) 멱등성 키. 지문에 동작·대상·사유를 넣어, 실행에 쓴 키로 취소를 보내는 식의 재사용을 가려낸다.
        String fingerprint = IdempotencyFingerprint.of(
                operation, String.valueOf(transactionId), reasonCode == null ? "" : reasonCode);
        Optional<InternalTransactionResponse> stored = idempotencyStore.find(
                idempotencyKey, fingerprint, operation, InternalTransactionResponse.class);
        if (stored.isPresent()) {
            return stored.get();
        }

        // 3) 실행·취소. 같은 트랜잭션에 참여하며, 이미 잠근 거래 행을 다시 잠그는 것은 대기 없이 통과한다.
        action.accept(transactionId, reasonCode);

        // 4) 키와 응답 저장
        InternalTransactionResponse response = assemble(tx);
        idempotencyStore.save(idempotencyKey, fingerprint, operation, response);
        return response;
    }

    private InternalTransactionResponse assemble(Transaction tx) {
        return InternalTransactionResponse.of(
                tx, holdRepository.findByTransactionId(tx.getId()).orElse(null), businessClock);
    }
}
