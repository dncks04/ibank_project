package com.ibank.domain.transaction.search;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 거래 검색·리포트 서비스.
 *
 * <p>소유권 검증은 JPA로, 조회는 MyBatis로 한다. 둘이 같은 DataSource를 쓰므로
 * {@code @Transactional(readOnly = true)} 하나로 같은 트랜잭션·커넥션에 묶인다.
 *
 * <p>매퍼에는 검증이 끝난 계좌 ID만 넘긴다. 클라이언트가 보낸 계좌번호를 그대로 SQL에 흘리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TransactionSearchService {

    private final AccountRepository accountRepository;
    private final TransactionSearchMapper searchMapper;

    @Transactional(readOnly = true)
    public PageResponse<TransactionRow> search(String accountNumber, Long userId,
                                               TransactionSearchCondition raw) {
        Account account = resolveOwnedAccount(accountNumber, userId);
        TransactionSearchCondition condition = raw.withAccountId(account.getId());

        List<TransactionRow> rows = searchMapper.search(condition);
        long total = searchMapper.countSearch(condition);

        return PageResponse.of(rows, condition.page(), condition.size(), total);
    }

    @Transactional(readOnly = true)
    public List<MonthlySummaryRow> monthlySummary(String accountNumber, Long userId, int year) {
        Account account = resolveOwnedAccount(accountNumber, userId);
        return searchMapper.monthlySummary(account.getId(), year);
    }

    private Account resolveOwnedAccount(String accountNumber, Long userId) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException(accountNumber);
        }
        return account;
    }
}
