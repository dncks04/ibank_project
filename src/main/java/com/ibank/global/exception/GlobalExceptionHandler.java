package com.ibank.global.exception;

import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리.
 *
 * 보안 원칙: 계좌번호·잔액·loginId 등 내부 식별자나 원시 DB 오류는 클라이언트로 노출하지 않는다.
 * 상세 원인은 서버 로그로만 남기고, 클라이언트에는 일반화된 메시지를 반환한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 도메인 비즈니스 예외 — 각 예외가 보유한 상태/클라이언트 메시지를 사용.
     * 상세 메시지는 로그로만 남긴다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("{}: {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getClientMessage()));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientBalance(InsufficientBalanceException e) {
        // 예외 메시지에는 계좌·잔액 상세가 있으므로 로그로만 남기고 클라이언트에는 일반화
        log.warn("잔액 부족: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiResponse.error("잔액이 부족합니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        // 원문 메시지에 내부 식별자가 섞일 수 있으므로 클라이언트에는 일반화하고 상세는 로그로만 남긴다.
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("요청을 처리할 수 없습니다."));
    }

    @ExceptionHandler(AccountAccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccountAccessDenied(AccountAccessDeniedException e) {
        // 어떤 계좌인지 노출하지 않는다 (계좌 존재 여부 추측 방지)
        log.warn("계좌 접근 거부: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("해당 계좌에 접근 권한이 없습니다."));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("낙관적 락 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("동시 요청 충돌이 발생했습니다. 재시도해 주세요."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        // 멱등성 키/유니크 제약 위반 등 — 원시 SQL 오류를 노출하지 않고 409로 일반화
        log.warn("데이터 무결성 위반: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("이미 처리되었거나 중복된 요청입니다."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        // 사용자 대상 상태 위반은 BusinessException으로 표현한다. 여기로 오는 건 예기치 못한 내부 상태이므로
        // 원문 대신 일반화된 메시지를 반환하고 상세는 로그로만 남긴다.
        log.warn("잘못된 상태: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("요청을 처리할 수 없습니다."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        // 필수 쿼리 파라미터 누락 (예: 해지 멱등성 키) — 400으로 명확히 반환
        log.warn("필수 파라미터 누락: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getParameterName() + ": 필수 파라미터입니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getAllErrors().stream()
                .filter(err -> err instanceof FieldError)
                .map(err -> ((FieldError) err).getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("입력값이 올바르지 않습니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    /**
     * 매핑되지 않은 예기치 못한 예외의 최후 방어선.
     * 스택트레이스·원시 오류가 응답으로 새어 나가지 않도록 일반화된 500을 반환하고,
     * 원인은 스택트레이스와 함께 서버 로그에만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
