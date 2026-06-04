package com.ibank.domain.account.controller;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.service.AccountService;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> open(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AccountOpenRequest request) {
        AccountResponse response = accountService.openAccount(userDetails.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getMyAccounts(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<AccountResponse> accounts = accountService.getMyAccounts(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String accountNumber) {
        AccountResponse response = accountService.getAccount(accountNumber, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
