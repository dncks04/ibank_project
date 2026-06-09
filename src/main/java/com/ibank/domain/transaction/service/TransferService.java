package com.ibank.domain.transaction.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.SameAccountTransferException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.global.audit.Audited;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;

    /** 멱등성 키로 기존 거래를 단일 쿼리로 조회. 중복 요청이면 기존 결과를 반환한다. */
    private Optional<TransferResponse> findByIdempotencyKey(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey).map(TransferResponse::from);
    }

    /**
     * 이체 처리 - 비관적 락 사용.
     *
     * 소유권:     출금 계좌(fromAccount)는 반드시 요청자 본인 소유여야 한다. 타인 계좌 출금을 차단한다.
     * 데드락 방지: account_number 오름차순으로 락 획득 (전역 락 순서 고정).
     * 멱등성:     락 획득 전 1차 검증, 락 획득 후 2차 재검증 (double-checked locking).
     *             READ_COMMITTED에서 락 해제 후 상대 스레드가 커밋된 행을 볼 수 있음을 이용.
     */
    @Audited(action = "TRANSFER", target = "#request.fromAccountNumber + '→' + #request.toAccountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transfer(Long userId, TransferRequest request) {
        // 동일 계좌 이체 차단: 락/멱등성 키 소모·무의미한 거래·원장 leg 생성 전에 fail-fast
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new SameAccountTransferException(request.fromAccountNumber());
        }

        // 1차 검증 (락 없음): 명백한 중복 요청 빠른 반환
        Optional<TransferResponse> existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        // 데드락 방지: 항상 계좌 번호 오름차순으로 락 획득
        List<String> sorted = List.of(request.fromAccountNumber(), request.toAccountNumber())
                .stream().sorted().toList();

        Account first = accountRepository.findByAccountNumberWithLock(sorted.get(0))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sorted.get(1))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(1)));

        // 2차 검증 (락 보유 상태): 동시 요청이 동일 key로 락을 대기 후 진입한 경우 방어
        // READ_COMMITTED이므로 락 대기 중 상대 스레드가 커밋하면 이 시점에 보임
        existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Account fromAccount = first.getAccountNumber().equals(request.fromAccountNumber()) ? first : second;
        Account toAccount   = first.getAccountNumber().equals(request.toAccountNumber())   ? first : second;

        // 소유권 검증: 출금 계좌는 반드시 요청자 본인 소유여야 한다 (타인 계좌 출금 차단)
        if (!fromAccount.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.fromAccountNumber());
        }

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
        // 복식부기: 출금 계좌 DEBIT, 입금 계좌 CREDIT (합이 0이 되는 두 leg)
        ledgerService.record(transaction, fromAccount, LedgerDirection.DEBIT, request.amount());
        ledgerService.record(transaction, toAccount, LedgerDirection.CREDIT, request.amount());
        return TransferResponse.from(transaction);
    }

    /**
     * HTTP 입금 — 소유권 검증 + 비관적 락.
     * 사용자 요청은 단건이므로 재시도 복잡성 없이 비관적 락으로 단순하게 처리.
     */
    @Audited(action = "DEPOSIT", target = "#request.accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse depositForUser(Long userId, DepositRequest request) {
        Optional<TransferResponse> existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.accountNumber());
        }

        existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        account.deposit(request.amount());

        Transaction transaction = Transaction.builder()
                .toAccount(account)
                .amount(request.amount())
                .type(Transaction.TransactionType.DEPOSIT)
                .description(request.description())
                .build()
                .withIdempotencyKey(request.idempotencyKey());
        transaction.complete();

        transactionRepository.save(transaction);
        ledgerService.record(transaction, account, LedgerDirection.CREDIT, request.amount());
        return TransferResponse.from(transaction);
    }

    /**
     * 출금 — 소유권 검증 + 비관적 락.
     * 잔액 초과 출금 방지를 위해 반드시 비관적 락 사용.
     */
    @Audited(action = "WITHDRAW", target = "#request.accountNumber")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse withdraw(Long userId, WithdrawRequest request) {
        Optional<TransferResponse> existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Account account = accountRepository.findByAccountNumberWithLock(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(request.accountNumber());
        }

        existing = findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        account.withdraw(request.amount());

        Transaction transaction = Transaction.builder()
                .fromAccount(account)
                .amount(request.amount())
                .type(Transaction.TransactionType.WITHDRAWAL)
                .description(request.description())
                .build()
                .withIdempotencyKey(request.idempotencyKey());
        transaction.complete();

        transactionRepository.save(transaction);
        ledgerService.record(transaction, account, LedgerDirection.DEBIT, request.amount());
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
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        account.deposit(amount);

        Transaction transaction = Transaction.builder()
                .toAccount(account)
                .amount(amount)
                .type(Transaction.TransactionType.DEPOSIT)
                .build()
                .withIdempotencyKey(idempotencyKey);
        transaction.complete();

        transactionRepository.save(transaction);
        ledgerService.record(transaction, account, LedgerDirection.CREDIT, amount);
    }
}
