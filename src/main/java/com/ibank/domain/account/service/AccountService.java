package com.ibank.domain.account.service;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotEmptyException;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final SecureRandom secureRandom = new SecureRandom();

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;

    @Audited(action = "ACCOUNT_OPEN", target = "#result.accountNumber")
    @Transactional
    public AccountResponse openAccount(Long userId, AccountOpenRequest request) {
        // 멱등성 검증: 같은 키로 이미 만든 계좌가 있으면 새로 만들지 않고 그대로 반환한다.
        // 재시도(앞선 요청이 이미 커밋된 경우)는 여기서 흡수된다. 드물게 같은 키의 두 요청이
        // 동시에 진행되면 UNIQUE 제약 위반이 발생하고, 이는 GlobalExceptionHandler에서 409로
        // 매핑된다(클라이언트는 재시도 시 위 replay로 동일 결과를 얻는다).
        Optional<AccountResponse> replay = replayOpenIfPresent(request.idempotencyKey(), userId);
        if (replay.isPresent()) {
            return replay.get();
        }

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Account account = Account.builder()
                .accountNumber(generateUniqueAccountNumber())
                .owner(owner)
                .initialBalance(request.initialBalance())
                .openIdempotencyKey(request.idempotencyKey())
                .build();

        // saveAndFlush: UNIQUE 위반을 이 시점에 DataIntegrityViolationException으로 드러내고,
        // 원장 기록(아래) 전에 계좌가 확실히 영속화되도록 한다.
        Account saved = accountRepository.saveAndFlush(account);
        // 초기 잔액도 원장에 기록하여 잔액 == 원장 합계 불변식을 유지
        ledgerService.recordOpening(saved, request.initialBalance());
        return AccountResponse.from(saved);
    }

    /**
     * 개설 멱등성 키로 기존 계좌를 조회해 replay 여부를 판단한다.
     * 기존 계좌가 있으면 요청자 본인 소유인지 검증한 뒤 반환하고(다른 사용자의 키 도용 차단),
     * 없으면 {@code Optional.empty()}.
     */
    private Optional<AccountResponse> replayOpenIfPresent(String idempotencyKey, Long userId) {
        return accountRepository.findByOpenIdempotencyKey(idempotencyKey)
                .map(account -> {
                    validateOwnership(account, userId);
                    return AccountResponse.from(account);
                });
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        validateOwnership(account, userId);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(Long userId) {
        return accountRepository.findAllByOwnerId(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    // 계좌번호: YYYYMMDD(8) + 랜덤6자리 = 14자리
    private String generateUniqueAccountNumber() {
        String prefix = LocalDate.now().format(DATE_FMT);
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = prefix + String.format("%06d", secureRandom.nextInt(1_000_000));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("계좌번호 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Audited(action = "ACCOUNT_CLOSE", target = "#accountNumber")
    @Transactional
    public void closeAccount(String accountNumber, Long userId, String idempotencyKey) {
        Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        validateOwnership(account, userId);

        // 멱등성: 같은 키로 이미 해지된 계좌면 재요청을 성공으로 흡수 (중복 해지 방지)
        if (account.getStatus() == Account.AccountStatus.CLOSED
                && idempotencyKey.equals(account.getCloseIdempotencyKey())) {
            return;
        }

        if (account.getBalance().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new AccountNotEmptyException();
        }

        // 이미 CLOSED(다른 키)면 close() 내부 validateActive()가 InactiveAccountException을 던진다.
        account.close(idempotencyKey);
    }

    private void validateOwnership(Account account, Long userId) {
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(account.getAccountNumber());
        }
    }
}
