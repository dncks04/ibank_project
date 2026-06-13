package com.ibank.domain.fraud.repository;

import com.ibank.domain.fraud.entity.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.Status status);
}
