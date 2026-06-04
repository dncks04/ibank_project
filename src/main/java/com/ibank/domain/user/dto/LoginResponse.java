package com.ibank.domain.user.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        String loginId,
        String name
) {
    public static LoginResponse of(String token, String loginId, String name) {
        return new LoginResponse(token, "Bearer", loginId, name);
    }
}
