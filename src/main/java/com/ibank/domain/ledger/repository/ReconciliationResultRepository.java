package com.ibank.domain.ledger.repository;

import com.ibank.domain.ledger.entity.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {
    long countByConsistentFalse();
    long countByJobExecutionId(Long jobExecutionId);
}
