package com.ibank.domain.fraud.repository;

import com.ibank.domain.fraud.entity.FraudAlert;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.Status status);

    /**
     * 검토(승인/반려) 처리용 비관적 락 조회. 같은 경보에 대한 동시 검토를 직렬화하여,
     * 한 운영자의 승인(자금 이동)과 다른 운영자의 반려(거래 취소)가 동시에 일어나는
     * 이중 처리를 방지한다. 락 획득 후 최신 커밋 상태를 다시 읽으므로 호출부에서 OPEN 여부를 재검증한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM FraudAlert a WHERE a.id = :id")
    Optional<FraudAlert> findByIdForUpdate(@Param("id") Long id);
}
