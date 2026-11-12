package com.ibank.internal.transaction;

import com.ibank.global.response.ApiResponse;
import com.ibank.internal.web.IdempotencyKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보류 거래 내부 API. {@code ROLE_SYSTEM} 토큰만 접근한다(SecurityConfig의 {@code /internal/**}).
 *
 * <ul>
 *   <li>GET  /internal/transactions/{id}         — 거래·홀드 상태</li>
 *   <li>POST /internal/transactions/{id}/execute — 보류 거래 실행</li>
 *   <li>POST /internal/transactions/{id}/cancel  — 보류 거래 취소</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/transactions")
@RequiredArgsConstructor
public class InternalTransactionController {

    private final InternalTransactionService service;

    /** 실행·취소 요청 본문. 본문 자체가 없어도 된다. */
    public record ResolutionRequest(
            @Pattern(regexp = "[A-Z0-9_]{1,50}", message = "대문자·숫자·밑줄 1~50자여야 합니다.") String reasonCode
    ) {}

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InternalTransactionResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<InternalTransactionResponse>> execute(
            @PathVariable Long id,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody(required = false) ResolutionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.execute(id, IdempotencyKeys.validated(idempotencyKey), reasonOf(request))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<InternalTransactionResponse>> cancel(
            @PathVariable Long id,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody(required = false) ResolutionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.cancel(id, IdempotencyKeys.validated(idempotencyKey), reasonOf(request))));
    }

    private static String reasonOf(ResolutionRequest request) {
        return request != null ? request.reasonCode() : null;
    }
}
