package com.ibank.internal.customer;

import com.ibank.global.response.ApiResponse;
import com.ibank.global.response.PageResponse;
import com.ibank.internal.customer.InternalCustomerService.CustomerProfileResponse;
import com.ibank.internal.customer.InternalCustomerService.DecisionTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 판정용 고객·거래 정보 내부 API. {@code ROLE_SYSTEM} 전용.
 *
 * <ul>
 *   <li>GET /internal/customers/{id}/profile — 나이, 가입일, 보유 계좌</li>
 *   <li>GET /internal/accounts/{accountNumber}/transactions?from=&amp;to= — 거래 이력</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalCustomerController {

    private static final int MAX_PAGE_SIZE = 500;

    private final InternalCustomerService service;

    @GetMapping("/customers/{id}/profile")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> profile(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.profile(id)));
    }

    @GetMapping("/accounts/{accountNumber}/transactions")
    public ResponseEntity<ApiResponse<PageResponse<DecisionTransactionResponse>>> transactions(
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page는 0 이상, size는 1~" + MAX_PAGE_SIZE + " 범위여야 합니다.");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from은 to보다 앞서야 합니다.");
        }
        return ResponseEntity.ok(ApiResponse.success(service.transactions(accountNumber, from, to, page, size)));
    }
}
