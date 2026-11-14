package com.ibank.internal.account;

import com.ibank.global.response.ApiResponse;
import com.ibank.internal.account.InternalAccountService.AccountStatusResponse;
import com.ibank.internal.web.IdempotencyKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계좌 지급정지 내부 API. {@code ROLE_SYSTEM} 전용.
 *
 * <ul>
 *   <li>POST /internal/accounts/{accountNumber}/freeze   — 지급정지 (출금·이체 출금 불가, 입금 허용)</li>
 *   <li>POST /internal/accounts/{accountNumber}/unfreeze — 해제</li>
 * </ul>
 * 둘 다 {@code Idempotency-Key} 헤더와 사유 코드가 필수다.
 */
@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalAccountController {

    private final InternalAccountService service;

    public record FreezeRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}", message = "대문자·숫자·밑줄 1~50자여야 합니다.") String reasonCode
    ) {}

    @PostMapping("/{accountNumber}/freeze")
    public ResponseEntity<ApiResponse<AccountStatusResponse>> freeze(
            @PathVariable String accountNumber,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody FreezeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.freeze(accountNumber, IdempotencyKeys.validated(idempotencyKey), request.reasonCode())));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    public ResponseEntity<ApiResponse<AccountStatusResponse>> unfreeze(
            @PathVariable String accountNumber,
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody FreezeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.unfreeze(accountNumber, IdempotencyKeys.validated(idempotencyKey), request.reasonCode())));
    }
}
