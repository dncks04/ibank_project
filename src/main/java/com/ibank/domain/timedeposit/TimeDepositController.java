package com.ibank.domain.timedeposit;

import com.ibank.domain.timedeposit.TimeDepositDtos.OpenRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminateRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminationResponse;
import com.ibank.domain.timedeposit.TimeDepositDtos.TimeDepositResponse;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정기예금 API.
 *
 * <ul>
 *   <li>POST /api/time-deposits — 가입 (입출금 계좌에서 자금 이동)</li>
 *   <li>POST /api/time-deposits/{accountNumber}/terminate — 해지 (만기 전이면 중도해지)</li>
 * </ul>
 * 정기예금 계좌의 조회는 일반 계좌 조회(GET /api/accounts/me, /api/accounts/{no})를 그대로 쓴다.
 */
@RestController
@RequestMapping("/api/time-deposits")
@RequiredArgsConstructor
public class TimeDepositController {

    private final TimeDepositService service;

    @PostMapping
    public ResponseEntity<ApiResponse<TimeDepositResponse>> open(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody OpenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.open(userDetails.getUserId(), request)));
    }

    @PostMapping("/{accountNumber}/terminate")
    public ResponseEntity<ApiResponse<TerminationResponse>> terminate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String accountNumber,
            @Valid @RequestBody TerminateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.terminate(userDetails.getUserId(), accountNumber, request)));
    }
}
