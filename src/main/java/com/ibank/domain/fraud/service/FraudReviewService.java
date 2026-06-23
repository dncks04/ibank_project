package com.ibank.domain.fraud.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.fraud.dto.FraudAlertResponse;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.exception.FraudAlertAlreadyResolvedException;
import com.ibank.domain.fraud.exception.FraudAlertNotFoundException;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.HeldTransactionNotFoundException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionLimitService;
import com.ibank.global.audit.Audited;
import com.ibank.global.metrics.IbankMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 이상거래로 보류(HELD)된 이체에 대한 운영자 검토 처리.
 *
 * <p>FDS는 의심 이체를 차단이 아니라 <b>보류</b>한다({@code TransferService.holdSuspiciousTransfer}).
 * 자금은 이동하지 않은 채 거래는 HELD, 경보는 OPEN으로 남는다. 이 서비스가 그 경보를 받아
 * <b>승인(release)</b> 시 실제 이체를 수행하거나 <b>반려(reject)</b> 시 거래를 취소한다.
 *
 * <p>승인은 보류 시점이 아니라 <b>검토 시점</b>의 잔액·한도를 다시 검증해야 하므로,
 * 출금/입금 계좌의 비관적 락을 (데드락 방지를 위해 계좌번호 오름차순으로) 재획득한 뒤
 * {@code TransferService.transfer}와 동일한 경로(검증 → withdraw/deposit → 복식부기 분개)로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class FraudReviewService {

    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionLimitService transactionLimitService;
    private final LedgerService ledgerService;
    private final IbankMetrics metrics;

    /** 상태별 경보 목록(최신순). 운영자 검토 큐 조회용. */
    @Transactional(readOnly = true)
    public List<FraudAlertResponse> list(FraudAlert.Status status) {
        return fraudAlertRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(FraudAlertResponse::from)
                .toList();
    }

    /**
     * 보류된 이체를 승인해 실제로 자금을 옮긴다. 검토 시점의 잔액·한도를 다시 검증하므로,
     * 잔액 부족 등으로 검증에 실패하면 트랜잭션이 롤백되어 경보는 OPEN으로 남는다(재검토/반려 가능).
     */
    @Audited(action = "FRAUD_RELEASE", target = "#alertId")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse release(Long alertId) {
        FraudAlert alert = loadOpenAlertForReview(alertId);
        Transaction tx = loadHeldTransaction(alert);

        String fromNumber = tx.getFromAccount().getAccountNumber();
        String toNumber = tx.getToAccount().getAccountNumber();
        BigDecimal amount = tx.getAmount();

        // 데드락 방지: 항상 계좌번호 오름차순으로 락 획득 (TransferService와 동일한 전역 순서)
        List<String> sorted = List.of(fromNumber, toNumber).stream().sorted().toList();
        Account first = accountRepository.findByAccountNumberWithLock(sorted.get(0))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(0)));
        Account second = accountRepository.findByAccountNumberWithLock(sorted.get(1))
                .orElseThrow(() -> new AccountNotFoundException(sorted.get(1)));
        Account fromAccount = first.getAccountNumber().equals(fromNumber) ? first : second;
        Account toAccount = first.getAccountNumber().equals(toNumber) ? first : second;

        // 검토 시점 재검증: 단건 한도 → 1일 누적 출금 한도(락 보유 상태) → 잔액(withdraw 내부 검증)
        transactionLimitService.validateAmount(amount);
        transactionLimitService.validateDailyOutflow(fromAccount, amount);

        fromAccount.withdraw(amount);
        toAccount.deposit(amount);

        tx.release();
        // 복식부기: 보류 시점에는 분개하지 않았으므로 승인 시점에 출금 DEBIT + 입금 CREDIT 기록 (합 0)
        ledgerService.recordTransfer(tx, fromAccount, toAccount, amount);
        alert.resolve();

        metrics.countOperation("FRAUD_RELEASE", "SUCCESS");
        return TransferResponse.from(tx);
    }

    /**
     * 보류된 이체를 반려한다. 자금을 옮기지 않고 거래를 취소(CANCELLED)하며 경보를 처리한다.
     * 원장 항목을 만들지 않으므로 잔액·정산 불변식에 영향이 없다.
     */
    @Audited(action = "FRAUD_REJECT", target = "#alertId")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse reject(Long alertId) {
        FraudAlert alert = loadOpenAlertForReview(alertId);
        Transaction tx = loadHeldTransaction(alert);

        tx.reject();
        alert.resolve();

        metrics.countOperation("FRAUD_REJECT", "SUCCESS");
        return TransferResponse.from(tx);
    }

    /**
     * 검토 대상 경보를 비관적 락으로 로드하고 OPEN 상태를 재검증한다.
     *
     * <p>같은 경보에 대한 동시 검토를 직렬화한다. 먼저 락을 얻은 쪽이 처리(RESOLVED)하면,
     * 나중에 락을 얻은 쪽은 최신 상태를 다시 읽어 OPEN이 아님을 확인하고 즉시 중단한다.
     * 덕분에 승인(자금 이동)과 반려(거래 취소)가 같은 경보에 동시에 적용되는 일이 없다.
     */
    private FraudAlert loadOpenAlertForReview(Long alertId) {
        FraudAlert alert = fraudAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new FraudAlertNotFoundException(alertId));
        if (alert.getStatus() != FraudAlert.Status.OPEN) {
            throw new FraudAlertAlreadyResolvedException(alertId);
        }
        return alert;
    }

    private Transaction loadHeldTransaction(FraudAlert alert) {
        return transactionRepository.findById(alert.getTransactionId())
                .filter(t -> t.getStatus() == Transaction.TransactionStatus.HELD)
                .orElseThrow(() -> new HeldTransactionNotFoundException(alert.getTransactionId()));
    }
}
