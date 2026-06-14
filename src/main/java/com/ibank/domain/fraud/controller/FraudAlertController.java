package com.ibank.domain.fraud.controller;

import com.ibank.domain.fraud.dto.FraudAlertResponse;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.service.FraudReviewService;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 이상거래 경보 검토 관리자 API. {@code ROLE_ADMIN}만 접근 가능(SecurityConfig에서 {@code /api/admin/**} 보호).
 *
 * <ul>
 *   <li>GET  /api/admin/fraud-alerts?status=OPEN — 검토 대기 경보 목록</li>
 *   <li>POST /api/admin/fraud-alerts/{id}/release — 보류 이체 승인(자금 이동)</li>
 *   <li>POST /api/admin/fraud-alerts/{id}/reject  — 보류 이체 반려(취소)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudReviewService fraudReviewService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<FraudAlertResponse>>> list(
            @RequestParam(defaultValue = "OPEN") FraudAlert.Status status) {
        return ResponseEntity.ok(ApiResponse.success(fraudReviewService.list(status)));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<ApiResponse<TransferResponse>> release(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(fraudReviewService.release(id)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<TransferResponse>> reject(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(fraudReviewService.reject(id)));
    }
}
