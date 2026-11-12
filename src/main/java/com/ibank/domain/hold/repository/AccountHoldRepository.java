package com.ibank.domain.hold.repository;

import com.ibank.domain.hold.entity.AccountHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountHoldRepository extends JpaRepository<AccountHold, Long> {

    Optional<AccountHold> findByTransactionId(Long transactionId);

    /** 계좌의 ACTIVE 홀드 합계. 가용잔액 = 원장잔액 − 이 값. 부분 인덱스(account_id WHERE ACTIVE)를 탄다. */
    @Query("""
            SELECT COALESCE(SUM(h.amount), 0) FROM AccountHold h
            WHERE h.account.id = :accountId
              AND h.status = com.ibank.domain.hold.entity.AccountHold.Status.ACTIVE
            """)
    BigDecimal sumActiveAmount(@Param("accountId") Long accountId);

    /** 여러 계좌의 ACTIVE 홀드 합계를 한 번에 조회한다(계좌 목록 응답용). 홀드가 없는 계좌는 결과에 없다. */
    @Query("""
            SELECT h.account.id AS accountId, SUM(h.amount) AS heldAmount FROM AccountHold h
            WHERE h.account.id IN :accountIds
              AND h.status = com.ibank.domain.hold.entity.AccountHold.Status.ACTIVE
            GROUP BY h.account.id
            """)
    List<HeldAmountByAccount> sumActiveAmountByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    interface HeldAmountByAccount {
        Long getAccountId();

        BigDecimal getHeldAmount();
    }
}
