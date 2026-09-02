package com.ibank.domain.transaction.controller;

import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransactionHistoryResponse;
import com.ibank.domain.transaction.dto.TransactionSearchRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.search.MonthlySummaryRow;
import com.ibank.domain.transaction.search.TransactionRow;
import com.ibank.domain.transaction.search.TransactionSearchCondition;
import com.ibank.domain.transaction.search.TransactionSearchService;
import com.ibank.domain.transaction.service.TransactionQueryService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.response.PageResponse;
import com.ibank.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

    private final TransferService transferService;
    private final TransactionQueryService transactionQueryService;
    private final TransactionSearchService transactionSearchService;

    @PostMapping("/transactions/deposit")
    public ResponseEntity<ApiResponse<TransferResponse>> deposit(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                transferService.depositForUser(userDetails.getUserId(), request)));
    }

    @PostMapping("/transactions/withdraw")
    public ResponseEntity<ApiResponse<TransferResponse>> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                transferService.withdraw(userDetails.getUserId(), request)));
    }

    @PostMapping("/transactions/transfer")
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                transferService.transfer(userDetails.getUserId(), request)));
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

    /**
     * 거래 상세 검색 (MyBatis 동적 조회)
     *
     * 위의 기간 조회를 대체하지 않고, 유형·상태·금액대·방향·키워드를 조합할 수 있는 검색을 따로 둔다.
     * 지정하지 않은 조건은 SQL에서 빠지므로, 조건을 하나도 주지 않으면 계좌 전체가 조회된다.
     *
     * GET /api/accounts/{accountNumber}/transactions/search
     *   ?from=2026-01-01T00:00:00&amp;types=TRANSFER,DEPOSIT&amp;minAmount=10000&amp;direction=OUT&amp;sort=AMOUNT
     */
    @GetMapping("/accounts/{accountNumber}/transactions/search")
    public ResponseEntity<ApiResponse<PageResponse<TransactionRow>>> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String accountNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<Transaction.TransactionType> types,
            @RequestParam(required = false) List<Transaction.TransactionStatus> statuses,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) TransactionSearchCondition.Direction direction,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) TransactionSearchCondition.SortKey sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // accountId는 서비스가 소유권 검증 후 채운다.
        TransactionSearchCondition condition = new TransactionSearchCondition(
                null, from, to, types, statuses, minAmount, maxAmount,
                direction, keyword, sort, page, size);

        PageResponse<TransactionRow> result =
                transactionSearchService.search(accountNumber, userDetails.getUserId(), condition);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 월별 거래 요약 리포트
     *
     * GET /api/accounts/{accountNumber}/transactions/summary?year=2026
     */
    @GetMapping("/accounts/{accountNumber}/transactions/summary")
    public ResponseEntity<ApiResponse<List<MonthlySummaryRow>>> monthlySummary(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String accountNumber,
            @RequestParam int year) {

        List<MonthlySummaryRow> result =
                transactionSearchService.monthlySummary(accountNumber, userDetails.getUserId(), year);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
