package com.ibank.internal.customer;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.transaction.search.DecisionHistoryRow;
import com.ibank.domain.transaction.search.TransactionSearchMapper;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.response.PageResponse;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FDS 서비스가 판정에 쓰는 고객·거래 정보. 판정에 필요한 최소한만 내보낸다.
 *
 * <p>고객 정보에는 이름·이메일·생년월일 원본을 싣지 않는다. 판정에 필요한 것은 생일이 아니라 나이이므로
 * 계산한 나이만 준다. 거래 이력에는 고객이 적은 메모(description)를 싣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InternalCustomerService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionSearchMapper searchMapper;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public CustomerProfileResponse profile(Long customerId) {
        User user = userRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        List<CustomerProfileResponse.AccountSummary> accounts = accountRepository.findAllByOwnerId(customerId).stream()
                .map(account -> new CustomerProfileResponse.AccountSummary(
                        account.getAccountNumber(),
                        account.getAccountType().name(),
                        account.getStatus().name(),
                        format(account.getCreatedAt())))
                .toList();
        return new CustomerProfileResponse(
                user.getId(),
                businessClock.ageOf(user.getBirthDate()),
                format(user.getCreatedAt()),
                accounts);
    }

    /**
     * 계좌의 거래 이력(최신순). 기간은 반열린 구간 [from, to).
     */
    @Transactional(readOnly = true)
    public PageResponse<DecisionTransactionResponse> transactions(String accountNumber, OffsetDateTime from,
                                                                  OffsetDateTime to, int page, int size) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        // 요청의 시각(오프셋 포함)을 저장된 시각과 같은 기준으로 바꿔 비교한다
        LocalDateTime fromStored = businessClock.toStorage(from);
        LocalDateTime toStored = businessClock.toStorage(to);

        List<DecisionTransactionResponse> rows = searchMapper
                .decisionHistory(account.getId(), fromStored, toStored, size, (long) page * size).stream()
                .map(this::toResponse)
                .toList();
        long total = searchMapper.countDecisionHistory(account.getId(), fromStored, toStored);
        return PageResponse.of(rows, page, size, total);
    }

    private DecisionTransactionResponse toResponse(DecisionHistoryRow row) {
        return new DecisionTransactionResponse(
                row.id(), row.type(), row.channel(), row.status(), row.direction(),
                row.amount().toPlainString(), row.counterpartAccountNumber(),
                format(row.createdAt()), format(row.completedAt()));
    }

    private String format(LocalDateTime stored) {
        return stored == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(businessClock.toBusinessOffset(stored));
    }

    /** 계약의 CustomerProfile. */
    public record CustomerProfileResponse(Long customerId, Integer age, String joinedAt, List<AccountSummary> accounts) {
        public record AccountSummary(String accountNumber, String accountType, String status, String openedAt) {}
    }

    /** 계약의 TransactionHistoryItem. */
    public record DecisionTransactionResponse(Long transactionId, String type, String channel, String status,
                                              String direction, String amount, String counterpartAccountNumber,
                                              String createdAt, String completedAt) {}

    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException(Long customerId) {
            super("고객을 찾을 수 없음: customerId=" + customerId);
        }
    }
}
