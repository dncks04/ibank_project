package com.ibank.domain.transaction.controller;

import com.ibank.domain.transaction.dto.TransactionHistoryResponse;
import com.ibank.domain.transaction.dto.TransactionSearchRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.service.TransactionQueryService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

    private final TransferService transferService;
    private final TransactionQueryService transactionQueryService;

    @PostMapping("/transactions/transfer")
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(ApiResponse.success(transferService.transfer(request)));
    }

    /**
     * 계좌 거래 내역 조회
     *
     * GET /api/accounts/{accountNumber}/transactions
     *   ?from=2026-01-01&to=2026-06-30&page=0&size=20
     */
    @GetMapping("/accounts/{accountNumber}/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionHistoryResponse>>> getHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        TransactionSearchRequest req = TransactionSearchRequest.of(from, to, page, size);
        Page<TransactionHistoryResponse> result =
                transactionQueryService.getHistory(accountNumber, userDetails.getUserId(), req);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
