package com.ibank.bulkload;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.service.LedgerService;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.security.pii.EmailCrypto;
import com.ibank.global.time.BusinessClock;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 입력 한 행을 DB에 적용한다. 입력 형식은 {@code docs/bulk-load-format.md}.
 *
 * <p>평소의 거래 경로({@code TransferService})를 타지 않는다. 과거 데이터를 넣는 것이므로
 * <b>FDS 판정, 거래 한도, 홀드, Outbox 이벤트를 건너뛴다.</b> 대신 원장만은 평소와 똑같이 남긴다 —
 * 계좌 개설과 모든 거래가 합이 0인 분개로 기록되어, 적재가 끝난 뒤 정산 배치가 그대로 통과해야 한다.
 *
 * <p>각 메서드는 검증을 모두 끝낸 뒤에만 변경을 시작한다. 검증에서 걸리면 {@link BulkLoadRowException}을 던지고,
 * 그때까지 DB는 건드리지 않은 상태다.
 * 그래서 이 예외는 트랜잭션을 롤백 대상으로 표시하지 않는다({@code noRollbackFor}) — 표시하면 문제 행 하나 때문에
 * 같은 청크의 정상 행까지 모두 롤백되고 스텝이 실패한다.
 *
 * <p>시각은 입력의 과거 시각으로 맞춘다. 엔티티는 생성 시각을 "지금"으로 채우므로, 저장(flush)한 뒤
 * 같은 트랜잭션에서 시각 컬럼만 고친다. 엔티티에 시각을 바꾸는 메서드를 열어 두지 않기 위함이다.
 */
@Component
@Profile("bulk-load")
@RequiredArgsConstructor
public class BulkLoadApplier {

    /** 적재한 거래의 멱등성 키 접두사. 같은 txRef를 다시 적재하면 이 키로 이미 있음을 안다. */
    static final String TX_KEY_PREFIX = "bulk-";
    private static final int MAX_TX_REF_LENGTH = 31; // 접두사 5 + 31 = 36 (idempotency_key 컬럼 길이)

    public enum Result { LOADED, ALREADY_LOADED }

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final PasswordEncoder passwordEncoder;
    private final BusinessClock businessClock;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final BulkLoadProperties properties;
    private final EmailCrypto emailCrypto;

    /** 기본 비밀번호의 해시. bcrypt는 느리므로 고객마다 다시 계산하지 않고 한 번만 만든다. */
    private volatile String defaultPasswordHash;

    // ------------------------------------------------------------------
    // 고객
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BulkLoadRowException.class)
    public Result applyCustomer(String line) {
        JsonNode row = parse(line);
        String loginId = text(row, "loginId");
        String name = text(row, "name");
        String email = text(row, "email");
        LocalDate birthDate = date(row, "birthDate");
        LocalDateTime joinedAt = time(row, "joinedAt");
        String password = optionalText(row, "password");
        if (loginId.length() < 4 || loginId.length() > 20) {
            throw new BulkLoadRowException("loginId는 4~20자여야 합니다");
        }
        if (name.length() > 30) {
            throw new BulkLoadRowException("name은 30자 이하여야 합니다");
        }

        if (userRepository.existsByLoginId(loginId)) {
            return Result.ALREADY_LOADED;
        }
        // 이메일은 유니크다. 저장 시점의 제약 위반은 청크 전체를 실패시키므로 미리 확인해 이 행만 건너뛴다.
        if (userRepository.existsByEmailBlindIndex(emailCrypto.blindIndex(email))) {
            throw new BulkLoadRowException("이미 쓰이는 이메일입니다(다른 loginId): " + loginId);
        }

        User user = userRepository.save(User.builder()
                .loginId(loginId)
                .password(password != null ? passwordEncoder.encode(password) : defaultPasswordHash())
                .name(name)
                .email(email)
                .birthDate(birthDate)
                .role(User.UserRole.ROLE_USER)
                .build());
        entityManager.flush();
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.valueOf(joinedAt), user.getId());
        return Result.LOADED;
    }

    // ------------------------------------------------------------------
    // 계좌
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BulkLoadRowException.class)
    public Result applyAccount(String line) {
        JsonNode row = parse(line);
        String accountNumber = text(row, "accountNumber");
        String loginId = text(row, "customerLoginId");
        BigDecimal initialBalance = amount(row, "initialBalance", true);
        LocalDateTime openedAt = time(row, "openedAt");
        if (!accountNumber.matches("\\d{10,20}")) {
            throw new BulkLoadRowException("accountNumber는 숫자 10~20자리여야 합니다");
        }

        if (accountRepository.existsByAccountNumber(accountNumber)) {
            return Result.ALREADY_LOADED;
        }
        User owner = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BulkLoadRowException("고객이 없습니다: " + loginId));

        Account account = accountRepository.save(Account.builder()
                .accountNumber(accountNumber)
                .owner(owner)
                .initialBalance(initialBalance)
                // 개설 멱등성 키는 UNIQUE라 비워 둘 수 없다. 적재분임을 알 수 있는 값으로 채운다.
                .openIdempotencyKey("bulk-open-" + accountNumber)
                .build());
        // 초기 잔액의 분개(고객 CREDIT + 클리어링 DEBIT). 0이면 분개가 없다.
        ledgerService.recordOpening(account, initialBalance);
        entityManager.flush();

        Timestamp at = Timestamp.valueOf(openedAt);
        jdbcTemplate.update("UPDATE accounts SET created_at = ? WHERE id = ?", at, account.getId());
        // 개설 분개의 두 leg(고객 leg와 시스템 leg)를 같은 시각으로. 시스템 leg는 account_id가 없어 journal_id로 찾는다.
        jdbcTemplate.update("""
                UPDATE ledger_entries SET created_at = ?
                WHERE journal_id IN (SELECT journal_id FROM ledger_entries
                                     WHERE account_id = ? AND transaction_id IS NULL)
                """, at, account.getId());
        return Result.LOADED;
    }

    // ------------------------------------------------------------------
    // 거래
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BulkLoadRowException.class)
    public Result applyTransaction(String line) {
        JsonNode row = parse(line);
        String txRef = text(row, "txRef");
        Transaction.TransactionType type = enumValue(Transaction.TransactionType.class, row, "type");
        BigDecimal amount = amount(row, "amount", false);
        LocalDateTime occurredAt = time(row, "occurredAt");
        Transaction.Channel channel = row.hasNonNull("channel")
                ? enumValue(Transaction.Channel.class, row, "channel") : Transaction.Channel.MOBILE;
        String description = optionalText(row, "description");
        String fromNumber = optionalText(row, "fromAccountNumber");
        String toNumber = optionalText(row, "toAccountNumber");
        if (txRef.length() > MAX_TX_REF_LENGTH) {
            throw new BulkLoadRowException("txRef는 " + MAX_TX_REF_LENGTH + "자 이하여야 합니다");
        }
        if (description != null && description.length() > 200) {
            throw new BulkLoadRowException("description은 200자 이하여야 합니다");
        }
        switch (type) {
            case DEPOSIT -> require(fromNumber == null && toNumber != null, "DEPOSIT은 toAccountNumber만 있어야 합니다");
            case WITHDRAWAL -> require(fromNumber != null && toNumber == null, "WITHDRAWAL은 fromAccountNumber만 있어야 합니다");
            case TRANSFER -> require(fromNumber != null && toNumber != null && !fromNumber.equals(toNumber),
                    "TRANSFER는 서로 다른 fromAccountNumber와 toAccountNumber가 있어야 합니다");
            default -> throw new BulkLoadRowException("적재할 수 없는 거래 유형입니다: " + type);
        }

        String key = TX_KEY_PREFIX + txRef;
        if (transactionRepository.findByIdempotencyKey(key).isPresent()) {
            return Result.ALREADY_LOADED;
        }

        // 계좌를 번호 오름차순으로 잠근다(평소 이체와 같은 순서). 적재 중에는 다른 거래가 없지만 순서를 맞춰 둔다.
        List<String> numbers = Stream.of(fromNumber, toNumber).filter(n -> n != null).sorted().toList();
        Account first = lock(numbers.get(0));
        Account second = numbers.size() > 1 ? lock(numbers.get(1)) : null;
        Account from = fromNumber == null ? null : (first.getAccountNumber().equals(fromNumber) ? first : second);
        Account to = toNumber == null ? null : (first.getAccountNumber().equals(toNumber) ? first : second);

        // ---- 검증 (여기까지 DB를 바꾸지 않았다) ----
        if (from != null) {
            require(from.getStatus() == Account.AccountStatus.ACTIVE, "출금 계좌가 활성 상태가 아닙니다: " + fromNumber);
            require(!from.isTimeDeposit(), "정기예금 계좌는 적재 대상이 아닙니다: " + fromNumber);
            require(from.getBalance().compareTo(amount) >= 0,
                    "잔액 부족: " + fromNumber + " 잔액 " + from.getBalance().toPlainString() + " < " + amount.toPlainString()
                            + " (거래가 시간순으로 정렬돼 있는지 확인하세요)");
        }
        if (to != null) {
            require(to.getStatus() == Account.AccountStatus.ACTIVE, "입금 계좌가 활성 상태가 아닙니다: " + toNumber);
            require(!to.isTimeDeposit(), "정기예금 계좌는 적재 대상이 아닙니다: " + toNumber);
        }

        // ---- 적용 ----
        if (from != null) {
            from.withdraw(amount);
        }
        if (to != null) {
            to.deposit(amount);
        }
        Transaction tx = Transaction.builder()
                .fromAccount(from)
                .toAccount(to)
                .amount(amount)
                .type(type)
                .description(description)
                .channel(channel)
                .build()
                .withIdempotency(key, null);
        tx.complete();
        transactionRepository.save(tx);
        switch (type) {
            case DEPOSIT -> ledgerService.recordDeposit(tx, to, amount);
            case WITHDRAWAL -> ledgerService.recordWithdrawal(tx, from, amount);
            default -> ledgerService.recordTransfer(tx, from, to, amount);
        }
        entityManager.flush();

        Timestamp at = Timestamp.valueOf(occurredAt);
        jdbcTemplate.update("UPDATE transactions SET created_at = ?, completed_at = ? WHERE id = ?", at, at, tx.getId());
        jdbcTemplate.update("UPDATE ledger_entries SET created_at = ? WHERE transaction_id = ?", at, tx.getId());
        return Result.LOADED;
    }

    // ------------------------------------------------------------------
    // 파싱 헬퍼
    // ------------------------------------------------------------------

    private JsonNode parse(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if (node == null || !node.isObject()) {
                throw new BulkLoadRowException("JSON 객체가 아닙니다");
            }
            return node;
        } catch (BulkLoadRowException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BulkLoadRowException("JSON 형식 오류");
        }
    }

    private static String text(JsonNode row, String field) {
        String value = optionalText(row, field);
        if (value == null) {
            throw new BulkLoadRowException("필수 값이 없습니다: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    /** 금액은 십진 문자열(또는 숫자)로 받고 소수 둘째 자리까지만 허용한다. */
    private static BigDecimal amount(JsonNode row, String field, boolean zeroAllowed) {
        String raw = text(row, field);
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BulkLoadRowException(field + "가 숫자가 아닙니다: " + raw);
        }
        if (value.scale() > 2) {
            throw new BulkLoadRowException(field + "는 소수 둘째 자리까지만 허용합니다: " + raw);
        }
        if (value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
            throw new BulkLoadRowException(field + "가 허용 범위를 벗어났습니다: " + raw);
        }
        return value;
    }

    private static LocalDate date(JsonNode row, String field) {
        String raw = text(row, field);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BulkLoadRowException(field + "는 yyyy-MM-dd 형식이어야 합니다: " + raw);
        }
    }

    /** 오프셋이 붙은 ISO-8601 시각을 저장 기준 시각으로 바꾼다. 미래 시각은 받지 않는다. */
    private LocalDateTime time(JsonNode row, String field) {
        String raw = text(row, field);
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BulkLoadRowException(field + "는 오프셋을 포함한 ISO-8601 형식이어야 합니다(예 2026-03-01T09:30:00+09:00): " + raw);
        }
        LocalDateTime stored = businessClock.toStorage(parsed);
        if (stored.isAfter(businessClock.nowForStorage())) {
            throw new BulkLoadRowException(field + "가 미래 시각입니다: " + raw);
        }
        return stored;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode row, String field) {
        String raw = text(row, field);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BulkLoadRowException(field + " 값이 올바르지 않습니다: " + raw);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new BulkLoadRowException(reason);
        }
    }

    private Account lock(String accountNumber) {
        return accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new BulkLoadRowException("계좌가 없습니다: " + accountNumber));
    }

    private String defaultPasswordHash() {
        String hash = defaultPasswordHash;
        if (hash == null) {
            String configured = properties.defaultPassword();
            // 기본 비밀번호가 없으면 아무도 모르는 무작위 값의 해시를 쓴다 = 로그인할 수 없는 계정
            hash = passwordEncoder.encode(configured != null && !configured.isBlank()
                    ? configured : UUID.randomUUID().toString());
            defaultPasswordHash = hash;
        }
        return hash;
    }
}
