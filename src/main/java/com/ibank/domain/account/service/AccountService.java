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
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String accountNumber = generateUniqueAccountNumber();

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .owner(owner)
                .initialBalance(request.initialBalance())
                .build();

        Account saved = accountRepository.save(account);
        // 초기 잔액도 원장에 기록하여 잔액 == 원장 합계 불변식을 유지
        ledgerService.recordOpening(saved, request.initialBalance());
        return AccountResponse.from(saved);
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
    public void closeAccount(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        validateOwnership(account, userId);

        if (account.getBalance().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new AccountNotEmptyException();
        }

        account.close();
    }

    private void validateOwnership(Account account, Long userId) {
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(account.getAccountNumber());
        }
    }
}
