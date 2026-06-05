package com.ibank.domain.user.controller;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RefreshRequest;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.TokenResponse;
import com.ibank.domain.user.dto.UserResponse;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.response.ApiResponse;
import com.ibank.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        UserResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        LoginResponse response = userService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        TokenResponse response = userService.refresh(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody RefreshRequest request) {
        userService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 전체 세션 즉시 무효화(계정 도용 신고/패닉 로그아웃). 인증 필요.
     * 호출자의 모든 access 토큰(즉시)·refresh 토큰을 무효화한다 → 모든 기기 재로그인 필요.
     */
    @PostMapping("/sessions/invalidate")
    public ResponseEntity<ApiResponse<Void>> invalidateAllSessions(
            @AuthenticationPrincipal CustomUserDetails principal) {
        userService.invalidateAllSessions(principal.getUserId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
