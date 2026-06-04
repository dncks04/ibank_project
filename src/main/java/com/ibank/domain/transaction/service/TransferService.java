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

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * 이체 처리 - 비관적 락 사용.
     *
     * 데드락 방지: account_number 오름차순으로 락 획득 (전역 락 순서 고정).
     * 멱등성:     락 획득 전 1차 검증, 락 획득 후 2차 재검증 (double-checked locking).
     *             READ_COMMITTED에서 락 해제 후 상대 스레드가 커밋된 행을 볼 수 있음을 이용.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transfer(TransferRequest request) {
        // 1차 검증 (락 없음): 명백한 중복 요청 빠른 반환
        if (transactionRepository.existsByIdempotencyKey(request.idempotencyKey())) {
            return TransferResponse.from(
                    transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow());
        }

        // 데드락 방지: 항상 계좌 번호 오름차순으로 락 획득
        List<String> sorted = List.of(request.fromAccountNumber(), request.toAccountNumber())
                .stream().sorted().toList();

        Account first = accountRepository.findByAccountNumberWithLock(sorted.get(0))
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + sorted.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sorted.get(1))
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다: " + sorted.get(1)));

        // 2차 검증 (락 보유 상태): 동시 요청이 동일 key로 락을 대기 후 진입한 경우 방어
        // READ_COMMITTED이므로 락 대기 중 상대 스레드가 커밋하면 이 시점에 보임
        if (transactionRepository.existsByIdempotencyKey(request.idempotencyKey())) {
            return TransferResponse.from(
                    transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow());
        }

        Account fromAccount = first.getAccountNumber().equals(request.fromAccountNumber()) ? first : second;
        Account toAccount   = first.getAccountNumber().equals(request.toAccountNumber())   ? first : second;

        fromAccount.withdraw(request.amount());
        toAccount.deposit(request.amount());

        Transaction transaction = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(request.amount())
                .type(Transaction.TransactionType.TRANSFER)
                .description(request.description())
                .build()
                .withIdempotencyKey(request.idempotencyKey());
        transaction.complete();

        transactionRepository.save(transaction);
        return TransferResponse.from(transaction);
    }

    /**
     * 입금 처리 - 낙관적 락 사용.
     * 충돌 시 @Retryable이 재시도. 동시 입금이 많지만 충돌 확률이 낮을 때 적합.
     */
    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 5,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 1000)
    )
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deposit(String accountNumber, BigDecimal amount, String idempotencyKey) {
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
