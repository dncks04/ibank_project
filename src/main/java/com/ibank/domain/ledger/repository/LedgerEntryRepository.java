package com.ibank.domain.ledger.repository;

import com.ibank.domain.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByAccountId(Long accountId);
}
