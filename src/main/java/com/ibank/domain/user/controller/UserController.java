package com.ibank.domain.user.controller;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RefreshRequest;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.TokenResponse;
import com.ibank.domain.user.dto.UserResponse;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
