package com.ibank.domain.interest.repository;

import com.ibank.domain.interest.entity.InterestAccrual;
import com.ibank.domain.interest.repository.dto.UnpaidInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, Long> {

    boolean existsByAccountIdAndAccrualDate(Long accountId, LocalDate accrualDate);

    /**
     * 계좌별 미지급 이자 합계. 월말 지급 배치가 계좌 단위로 지급액을 모으는 조회.
     * 합계가 0인 계좌(잔액 0으로만 적립된 경우)는 지급 대상이 아니므로 제외한다.
     */
    @Query("""
            SELECT a.accountId AS accountId, SUM(a.interestAmount) AS amount
            FROM InterestAccrual a
            WHERE a.paid = false
            GROUP BY a.accountId
            HAVING SUM(a.interestAmount) > 0
            """)
    List<UnpaidInterest> findUnpaidGroupedByAccount();

    List<InterestAccrual> findByAccountIdAndPaidFalse(Long accountId);
}
