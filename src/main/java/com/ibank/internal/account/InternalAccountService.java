package com.ibank.internal.account;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.outbox.DomainEvents;
import com.ibank.domain.outbox.EventType;
import com.ibank.domain.transaction.service.IdempotencyFingerprint;
import com.ibank.global.audit.Audited;
import com.ibank.internal.idempotency.InternalIdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 계좌 지급정지·해제. FDS·보호 흐름 서비스가 사기 피해가 의심되는 계좌에서 돈이 더 나가지 않게 막는다.
 *
 * <p>보류 거래 내부 API와 같은 순서로 처리한다: <b>계좌 행 락 → 멱등성 키 확인 → 상태 변경 → 키 저장</b>을
 * 한 트랜잭션으로. 계좌 락은 진행 중인 출금·이체와도 직렬화되므로, 지급정지가 커밋된 뒤에 가승인되는 거래는
 * 반드시 정지된 상태를 본다.
 *
 * <p>상태 자체도 멱등하다. 이미 정지된 계좌를 다른 키로 다시 정지해도 성공이다(상태는 그대로, 이벤트는 다시
 * 내지 않는다). 지급정지 시점에 묶여 있던 금액(ACTIVE 홀드)은 건드리지 않는다 — 보류 거래의 처분은 따로 결정한다.
 */
@Service
@RequiredArgsConstructor
public class InternalAccountService {

    private static final String OP_FREEZE = "ACCOUNT_FREEZE";
    private static final String OP_UNFREEZE = "ACCOUNT_UNFREEZE";

    /** 지급정지·해제 응답. */
    public record AccountStatusResponse(String accountNumber, String status, String reasonCode) {}

    private final AccountRepository accountRepository;
    private final InternalIdempotencyStore idempotencyStore;
    private final DomainEvents events;

    @Audited(action = "INTERNAL_ACCOUNT_FREEZE", target = "#accountNumber", detail = "#reasonCode")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountStatusResponse freeze(String accountNumber, String idempotencyKey, String reasonCode) {
        return change(OP_FREEZE, accountNumber, idempotencyKey, reasonCode);
    }

    @Audited(action = "INTERNAL_ACCOUNT_UNFREEZE", target = "#accountNumber", detail = "#reasonCode")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountStatusResponse unfreeze(String accountNumber, String idempotencyKey, String reasonCode) {
        return change(OP_UNFREEZE, accountNumber, idempotencyKey, reasonCode);
    }

    private AccountStatusResponse change(String operation, String accountNumber, String idempotencyKey,
                                         String reasonCode) {
        // 1) 계좌 행 락. 같은 계좌에 대한 요청과 진행 중인 출금·이체가 여기서 줄을 선다.
        Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        // 2) 멱등성 키
        String fingerprint = IdempotencyFingerprint.of(operation, accountNumber, reasonCode);
        Optional<AccountStatusResponse> stored = idempotencyStore.find(
                idempotencyKey, fingerprint, operation, AccountStatusResponse.class);
        if (stored.isPresent()) {
            return stored.get();
        }

        // 3) 상태 변경. 실제로 바뀐 경우에만 이벤트를 낸다.
        boolean changed = OP_FREEZE.equals(operation) ? account.freeze(reasonCode) : account.unfreeze();
        if (changed) {
            events.account(OP_FREEZE.equals(operation) ? EventType.ACCOUNT_FROZEN : EventType.ACCOUNT_UNFROZEN,
                    account, null, reasonCode);
        }

        // 4) 키와 응답 저장
        AccountStatusResponse response = new AccountStatusResponse(
                account.getAccountNumber(), account.getStatus().name(), reasonCode);
        idempotencyStore.save(idempotencyKey, fingerprint, operation, response);
        return response;
    }
}
