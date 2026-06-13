package com.ibank.domain.interest.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.interest.entity.InterestAccrual;
import com.ibank.domain.interest.repository.InterestAccrualRepository;
import com.ibank.domain.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 한 계좌의 미지급 이자를 지급한다. 한 계좌 = 한 트랜잭션.
 *
 * <p>멱등성·정합성: 계좌의 비관적 락을 쥔 상태에서 <b>미지급 적립분을 다시 읽어</b> 합산하므로,
 * 배치가 겹쳐 돌거나 재실행되어도 이미 지급(paid=true)된 적립분은 합산되지 않는다.
 * 입금·원장 분개·적립분 paid 표시가 한 트랜잭션으로 커밋되어, 잔액 증가와 원장 CREDIT이
 * 항상 함께 일어난다(정산 불변식 {@code balance == ledgerSum} 보존).
 */
@Service
@RequiredArgsConstructor
public class InterestPaymentService {

    private final AccountRepository accountRepository;
    private final InterestAccrualRepository interestAccrualRepository;
    private final LedgerService ledgerService;

    /**
     * 계좌의 미지급 이자를 모두 지급한다. 지급액이 없으면 아무 것도 하지 않는다.
     *
     * @return 지급된 이자 총액(0이면 미지급)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public BigDecimal payAccount(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        // 비관적 락: 동시 이체/출금과 잔액 갱신을 직렬화한다.
        Account locked = accountRepository.findByAccountNumberWithLock(account.getAccountNumber())
                .orElseThrow();

        // 락 보유 상태에서 미지급분을 다시 읽는다(겹친 실행/재실행에 대한 멱등성).
        List<InterestAccrual> unpaid = interestAccrualRepository.findByAccountIdAndPaidFalse(accountId);
        BigDecimal total = unpaid.stream()
                .map(InterestAccrual::getInterestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        locked.deposit(total);
        String journalId = ledgerService.recordInterestPayment(locked, total);
        unpaid.forEach(accrual -> accrual.markPaid(journalId));
        return total;
    }
}
