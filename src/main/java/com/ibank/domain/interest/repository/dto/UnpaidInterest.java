package com.ibank.domain.interest.repository.dto;

import java.math.BigDecimal;

/** 계좌별 미지급 이자 합계 projection. */
public interface UnpaidInterest {
    Long getAccountId();
    BigDecimal getAmount();
}
