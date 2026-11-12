package com.ibank.domain.timedeposit;

import com.ibank.domain.account.entity.Account;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 정기예금 가입·해지의 요청·응답. */
public final class TimeDepositDtos {

    private TimeDepositDtos() {
    }

    /**
     * 가입 요청.
     *
     * @param idempotencyKey       같은 키로 다시 요청하면 새로 가입하지 않고 처음 만든 정기예금을 돌려준다.
     * @param fundingAccountNumber 가입 금액이 빠져나갈 입출금 계좌(본인 소유)
     * @param termMonths           가입 기간(개월)
     */
    public record OpenRequest(
            @NotBlank @Size(max = 64) String idempotencyKey,
            @NotBlank String fundingAccountNumber,
            @NotNull @DecimalMin("1") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Min(1) int termMonths
    ) {}

    /**
     * 해지 요청.
     *
     * @param idempotencyKey      같은 키로 다시 요청하면 다시 해지하지 않고 처음 해지 결과를 돌려준다.
     * @param payoutAccountNumber 원금과 이자를 받을 입출금 계좌(본인 소유)
     */
    public record TerminateRequest(
            @NotBlank @Size(max = 36) String idempotencyKey,
            @NotBlank String payoutAccountNumber
    ) {}

    public record TimeDepositResponse(
            String accountNumber,
            BigDecimal principal,
            BigDecimal contractRate,
            LocalDate maturityDate,
            Account.AccountStatus status
    ) {
        public static TimeDepositResponse from(Account account) {
            return new TimeDepositResponse(account.getAccountNumber(), account.getBalance(),
                    account.getContractRate(), account.getMaturityDate(), account.getStatus());
        }
    }

    /**
     * 해지 결과.
     *
     * @param early       만기 전 해지였는지. true면 약정 금리 대신 중도해지 금리가 적용됐다.
     * @param appliedRate 실제 적용된 연이율
     */
    public record TerminationResponse(
            String accountNumber,
            String payoutAccountNumber,
            BigDecimal principal,
            BigDecimal interest,
            boolean early,
            BigDecimal appliedRate,
            Long transactionId
    ) {}
}
