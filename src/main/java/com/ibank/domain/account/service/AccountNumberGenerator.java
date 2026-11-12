package com.ibank.domain.account.service;

import com.ibank.domain.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 계좌번호 발급. YYYYMMDD(8) + 랜덤 6자리 = 14자리. 입출금 계좌와 정기예금이 같은 규칙을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AccountNumberGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom secureRandom = new SecureRandom();
    private final AccountRepository accountRepository;

    public String generate() {
        String prefix = LocalDate.now().format(DATE_FMT);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = prefix + String.format("%06d", secureRandom.nextInt(1_000_000));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("계좌번호 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
