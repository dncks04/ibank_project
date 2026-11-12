package com.ibank.internal.web;

import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.exception.AccountNotFoundException;
import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionNotFoundException;
import com.ibank.domain.transaction.exception.TransactionNotHeldException;
import com.ibank.global.response.ApiResponse;
import com.ibank.internal.customer.InternalCustomerService;
import com.ibank.internal.idempotency.InternalIdempotencyKeyConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 내부 API({@code com.ibank.internal})의 예외를 계약의 오류 코드로 바꾼다.
 * 계약은 {@code docs/contracts/ibank-internal.openapi.yaml}의 Error 응답 표.
 *
 * <p>고객 API의 전역 처리기와 따로 두는 이유: 호출 서비스는 사람용 메시지가 아니라 <b>코드</b>로 분기하므로
 * 모든 실패에 코드가 있어야 하고, 같은 예외라도 응답이 다르다(예: 한도 초과는 고객 API에서 400, 보류 거래 실행의
 * 재검증 실패로서는 "요청은 맞지만 지금은 처리할 수 없음"인 422).
 * 이 패키지의 컨트롤러에만 적용되고 전역 처리기보다 먼저 선택된다.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.ibank.internal")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalApiExceptionHandler {

    // ---- 400 ----

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingHeader(MissingRequestHeaderException e) {
        if (IdempotencyKeys.HEADER.equalsIgnoreCase(e.getHeaderName())) {
            return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다.");
        }
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getHeaderName() + ": 필수 헤더입니다.");
    }

    @ExceptionHandler(IdempotencyKeys.InvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiResponse<Void>> invalidKey(IdempotencyKeys.InvalidIdempotencyKeyException e) {
        return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception e) {
        log.warn("내부 API 잘못된 요청: {}", e.getClass().getSimpleName());
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.");
    }

    // ---- 404 ----

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> transactionNotFound(TransactionNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", e.getClientMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> accountNotFound(AccountNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
    }

    @ExceptionHandler(InternalCustomerService.CustomerNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> customerNotFound(InternalCustomerService.CustomerNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "고객을 찾을 수 없습니다.");
    }

    // ---- 409 ----

    @ExceptionHandler(TransactionNotHeldException.class)
    public ResponseEntity<ApiResponse<Void>> notHeld(TransactionNotHeldException e) {
        log.warn("내부 API: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, "TRANSACTION_NOT_HELD", e.getClientMessage());
    }

    @ExceptionHandler(InternalIdempotencyKeyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> keyConflict(InternalIdempotencyKeyConflictException e) {
        log.warn("내부 API: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "같은 Idempotency-Key가 다른 요청에 이미 사용되었습니다.");
    }

    /**
     * 같은 키를 서로 다른 대상에 동시에 쓴 경우: 대상이 달라 락으로 직렬화되지 않고 키의 유니크 제약에서 만난다.
     * 내부 API에서 유니크 제약이 걸리는 insert는 멱등성 키뿐이다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> uniqueViolation(DataIntegrityViolationException e) {
        log.warn("내부 API 무결성 위반: {}", e.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "같은 Idempotency-Key가 다른 요청에 이미 사용되었습니다.");
    }

    // ---- 422: 요청은 맞지만 실행 시점 재검증에서 걸렸다. 거래는 HELD로 남는다 ----

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiResponse<Void>> insufficient(InsufficientBalanceException e) {
        log.warn("내부 API 재검증 실패: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_AVAILABLE_BALANCE", "가용잔액이 부족합니다.");
    }

    @ExceptionHandler(DailyLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> dailyLimit(DailyLimitExceededException e) {
        log.warn("내부 API 재검증 실패: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "DAILY_LIMIT_EXCEEDED", e.getClientMessage());
    }

    @ExceptionHandler(TransactionLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> perTransactionLimit(TransactionLimitExceededException e) {
        log.warn("내부 API 재검증 실패: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "PER_TRANSACTION_LIMIT_EXCEEDED", e.getClientMessage());
    }

    @ExceptionHandler(InactiveAccountException.class)
    public ResponseEntity<ApiResponse<Void>> inactiveAccount(InactiveAccountException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE", e.getClientMessage());
    }

    // ---- 503: 일시적 실패. 같은 키로 재시도하면 된다 ----

    @ExceptionHandler({PessimisticLockingFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiResponse<Void>> temporarilyUnavailable(Exception e) {
        log.warn("내부 API 일시 실패: {}", e.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "잠시 후 같은 키로 다시 시도해 주세요.");
    }

    // ---- 500 ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("내부 API 처리되지 않은 예외", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
    }

    private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message, code));
    }
}
