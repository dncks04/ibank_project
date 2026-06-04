package com.ibank.domain.account.service;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
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

        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + accountNumber));

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

    @Transactional
    public void closeAccount(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + accountNumber));

        validateOwnership(account, userId);

        if (account.getBalance().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("잔액이 남아있는 계좌는 해지할 수 없습니다. 잔액: " + account.getBalance());
        }

        account.close();
    }

    private void validateOwnership(Account account, Long userId) {
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(account.getAccountNumber());
        }
    }
}
