package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.transaction.dto.TransactionHistoryResponse;
import com.ibank.domain.transaction.dto.TransactionSearchRequest;
import com.ibank.domain.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getHistory(
            String accountNumber, Long userId, TransactionSearchRequest req) {

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + accountNumber));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(accountNumber);
        }

        LocalDateTime from = req.from() != null ? req.from().atStartOfDay() : null;
        LocalDateTime to   = req.to()   != null ? req.to().atTime(23, 59, 59) : null;

        return transactionRepository.findByAccountIdAndDateRange(
                        account.getId(), from, to,
                        PageRequest.of(req.page(), req.size()))
                .map(tx -> TransactionHistoryResponse.from(tx, account.getId()));
    }
}
