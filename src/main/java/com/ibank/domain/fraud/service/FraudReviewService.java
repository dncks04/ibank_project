package com.ibank.domain.fraud.service;

import com.ibank.domain.fraud.dto.FraudAlertResponse;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.exception.FraudAlertAlreadyResolvedException;
import com.ibank.domain.fraud.exception.FraudAlertNotFoundException;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.global.audit.Audited;
import com.ibank.global.metrics.IbankMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이상거래로 보류(HELD)된 이체에 대한 운영자 검토 처리.
 *
 * <p><b>이관 전 임시 경로.</b> 보류 거래의 실행·취소 자체는 {@link TransactionProcessor}가 맡고
 * (FDS 서비스가 호출할 내부 API와 같은 로직), 이 서비스는 그 위에 관리자 검토용 경보의 상태만 관리한다.
 * 탐지 책임이 FDS 서비스로 넘어가는 단계에서 경보와 함께 삭제한다.
 *
 * <p>락 순서: 경보 행 → 거래 행 → 계좌. 경보 락은 이 서비스만 잡으므로 다른 경로와 순환하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FraudReviewService {

    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionProcessor transactionProcessor;
    private final IbankMetrics metrics;

    /** 상태별 경보 목록(최신순). 운영자 검토 큐 조회용. */
    @Transactional(readOnly = true)
    public List<FraudAlertResponse> list(FraudAlert.Status status) {
        return fraudAlertRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(FraudAlertResponse::from)
                .toList();
    }

    /**
     * 보류된 이체를 승인해 실제로 자금을 옮긴다. 검토 시점의 계좌 상태·한도·가용잔액을 다시 검증하므로,
     * 검증에 실패하면 트랜잭션이 롤백되어 거래는 HELD, 경보는 OPEN으로 남는다(재검토/반려 가능).
     */
    @Audited(action = "FRAUD_RELEASE", target = "#alertId")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse release(Long alertId) {
        FraudAlert alert = loadOpenAlertForReview(alertId);
        // 같은 트랜잭션에 참여한다: 실행과 경보 처리가 함께 커밋되거나 함께 롤백된다
        TransferResponse result = transactionProcessor.executeHeld(alert.getTransactionId());
        alert.resolve();

        metrics.countOperation("FRAUD_RELEASE", "SUCCESS");
        return result;
    }

    /**
     * 보류된 이체를 반려한다. 자금을 옮기지 않고 거래를 취소(CANCELLED)하며 묶인 금액을 푼다.
     * 원장 항목을 만들지 않으므로 잔액·정산 불변식에 영향이 없다.
     */
    @Audited(action = "FRAUD_REJECT", target = "#alertId")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse reject(Long alertId) {
        FraudAlert alert = loadOpenAlertForReview(alertId);
        TransferResponse result = transactionProcessor.cancelHeld(alert.getTransactionId());
        alert.resolve();

        metrics.countOperation("FRAUD_REJECT", "SUCCESS");
        return result;
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
}
