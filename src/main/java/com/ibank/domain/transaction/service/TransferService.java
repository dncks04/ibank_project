package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * 이체 처리 - 비관적 락 사용.
     * 데드락 방지: 항상 account_number 오름차순으로 락 획득.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transfer(TransferRequest request) {
        // 멱등성 검증: 동일 key로 중복 요청 시 기존 결과 반환
        if (transactionRepository.existsByIdempotencyKey(request.idempotencyKey())) {
            Transaction existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow();
            return TransferResponse.from(existing);
        }

        // 데드락 방지: 계좌 번호 기준 정렬 후 순서대로 락 획득
        List<String> sortedNumbers = List.of(request.fromAccountNumber(), request.toAccountNumber())
                .stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        Account first = accountRepository.findByAccountNumberWithLock(sortedNumbers.get(0))
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + sortedNumbers.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sortedNumbers.get(1))
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + sortedNumbers.get(1)));

        Account fromAccount = first.getAccountNumber().equals(request.fromAccountNumber()) ? first : second;
        Account toAccount = first.getAccountNumber().equals(request.toAccountNumber()) ? first : second;

        Transaction transaction = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(request.amount())
                .type(Transaction.TransactionType.TRANSFER)
                .description(request.description())
                .build()
                .withIdempotencyKey(request.idempotencyKey());

        try {
            fromAccount.withdraw(request.amount());
            toAccount.deposit(request.amount());
            transaction.complete();
        } catch (Exception e) {
            transaction.fail();
            transactionRepository.save(transaction);
            throw e;
        }

        transactionRepository.save(transaction);
        return TransferResponse.from(transaction);
    }

    /**
     * 낙관적 락 기반 입금 - 충돌 시 재시도.
     * 동시 입금이 빈번하지만 충돌 확률이 낮을 때 사용.
     */
    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deposit(String accountNumber, java.math.BigDecimal amount, String idempotencyKey) {
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + accountNumber));

        account.deposit(amount);

        Transaction transaction = Transaction.builder()
                .toAccount(account)
                .amount(amount)
                .type(Transaction.TransactionType.DEPOSIT)
                .build()
                .withIdempotencyKey(idempotencyKey);
        transaction.complete();

        transactionRepository.save(transaction);
    }
}
