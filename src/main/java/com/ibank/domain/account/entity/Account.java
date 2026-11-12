package com.ibank.domain.account.entity;

import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.account.exception.TimeDepositRestrictedException;
import com.ibank.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    // 계좌 유형. 정기예금은 가입·해지 외에는 직접 입출금할 수 없다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType accountType;

    // 정기예금 약정: 만기일(영업 타임존 기준)과 가입 시점에 고정된 연이율. 입출금 계좌는 둘 다 null.
    private LocalDate maturityDate;

    @Column(precision = 7, scale = 5)
    private BigDecimal contractRate;

    // 정기예금 해지 때 지급한 이자. 해지 전에는 null.
    @Column(precision = 19, scale = 2)
    private BigDecimal paidInterest;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 개설 멱등성 키: 같은 키 재요청은 기존 계좌를 replay한다 (중복 생성 방지). UNIQUE.
    @Column(length = 64, unique = true)
    private String openIdempotencyKey;

    // 해지 멱등성 키: 같은 키로 이미 해지된 계좌면 재요청을 성공으로 흡수한다 (중복 해지 방지).
    @Column(length = 64)
    private String closeIdempotencyKey;

    // 낙관적 락: 동시 수정 충돌 감지
    @Version
    private Long version;

    /** 입출금 계좌. */
    @Builder
    public Account(String accountNumber, User owner, BigDecimal initialBalance, String openIdempotencyKey) {
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.balance = initialBalance;
        this.status = AccountStatus.ACTIVE;
        this.accountType = AccountType.DEMAND;
        this.createdAt = LocalDateTime.now();
        this.openIdempotencyKey = openIdempotencyKey;
    }

    /**
     * 정기예금 계좌. 잔액 0으로 만들고, 가입 금액은 입출금 계좌에서 이체(분개)로 들어온다.
     * 처음부터 잔액을 넣어 만들면 그 돈이 어디서 왔는지 원장에 남지 않는다.
     */
    public static Account timeDeposit(String accountNumber, User owner, LocalDate maturityDate,
                                      BigDecimal contractRate, String openIdempotencyKey) {
        Account account = new Account(accountNumber, owner, BigDecimal.ZERO, openIdempotencyKey);
        account.accountType = AccountType.TIME_DEPOSIT;
        account.maturityDate = maturityDate;
        account.contractRate = contractRate;
        return account;
    }

    public void deposit(BigDecimal amount) {
        validateActive();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("입금액은 0보다 커야 합니다.");
        }
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        validateActive();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("출금액은 0보다 커야 합니다.");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(this.accountNumber, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void close(String idempotencyKey) {
        validateActive();
        this.status = AccountStatus.CLOSED;
        this.closeIdempotencyKey = idempotencyKey;
    }

    /** 정기예금 해지 때 지급한 이자를 남긴다. */
    public void recordPaidInterest(BigDecimal interest) {
        this.paidInterest = interest;
    }

    /** 거래에 쓸 수 있는 계좌인지 확인한다. 자금을 옮기기 전에 미리 검증할 때 쓴다. */
    public void ensureActive() {
        validateActive();
    }

    /**
     * 고객의 출금·이체로 돈을 내보낼 수 있는 계좌인지. 활성 상태의 입출금 계좌여야 한다.
     * 정기예금은 해지 절차로만 돈이 나간다.
     */
    public void ensureCanSend() {
        validateActive();
        if (this.accountType != AccountType.DEMAND) {
            throw new TimeDepositRestrictedException(this.accountNumber);
        }
    }

    /**
     * 고객의 입금·이체로 돈을 받을 수 있는 계좌인지. 활성 상태의 입출금 계좌여야 한다.
     * 정기예금은 가입 때 한 번만 자금이 들어온다.
     */
    public void ensureCanReceive() {
        validateActive();
        if (this.accountType != AccountType.DEMAND) {
            throw new TimeDepositRestrictedException(this.accountNumber);
        }
    }

    public boolean isTimeDeposit() {
        return this.accountType == AccountType.TIME_DEPOSIT;
    }

    private void validateActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new InactiveAccountException();
        }
    }

    public enum AccountStatus {
        ACTIVE, SUSPENDED, CLOSED
    }

    public enum AccountType {
        /** 입출금 계좌 */
        DEMAND,
        /** 정기예금 */
        TIME_DEPOSIT
    }
}
