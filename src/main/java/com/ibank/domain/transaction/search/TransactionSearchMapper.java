package com.ibank.domain.transaction.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 거래 조회 전용 매퍼. 쓰기는 JPA가 맡고 여기서는 읽기만 한다.
 *
 * <p>SQL은 {@code resources/mapper/TransactionSearchMapper.xml}에 있다. 동적 조건이 많아
 * 어노테이션보다 XML의 {@code <where>}/{@code <if>}/{@code <foreach>}가 읽기 쉽다고 보고 XML을 택했다.
 *
 * <p>매퍼는 소유권 검증이 끝난 {@code accountId}만 받는다. 검증은 {@link TransactionSearchService}에서 한다.
 */
@Mapper
public interface TransactionSearchMapper {

    /** 조건에 맞는 거래를 정렬·페이징해 조회한다. */
    List<TransactionRow> search(TransactionSearchCondition condition);

    /** {@link #search} 와 같은 조건의 총 건수. 페이징 응답의 totalElements 계산용. */
    long countSearch(TransactionSearchCondition condition);

    /**
     * 판정용 거래 이력(최신순). FDS 서비스가 규칙·모델의 입력으로 쓴다. 기간은 반열린 구간 [from, to)이며
     * 둘 다 null이면 전체다. 고객용 검색과 달리 채널·방향·완료 시각을 싣고 설명(description)은 싣지 않는다.
     */
    List<DecisionHistoryRow> decisionHistory(@Param("accountId") Long accountId,
                                             @Param("from") java.time.LocalDateTime from,
                                             @Param("to") java.time.LocalDateTime to,
                                             @Param("size") int size,
                                             @Param("offset") long offset);

    /** {@link #decisionHistory}와 같은 조건의 총 건수. */
    long countDecisionHistory(@Param("accountId") Long accountId,
                              @Param("from") java.time.LocalDateTime from,
                              @Param("to") java.time.LocalDateTime to);

    /** 한 해의 월별 입금/출금 합계와 건수. */
    List<MonthlySummaryRow> monthlySummary(@Param("accountId") Long accountId,
                                           @Param("year") int year);
}
