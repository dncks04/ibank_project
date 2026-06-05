package com.ibank.domain.user.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        String loginId,
        String name
) {
    public static LoginResponse of(String accessToken, String refreshToken, String loginId, String name) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", loginId, name);
    }

    /** 리프레시 토큰 없이 생성 (테스트/하위 호환용). */
    public static LoginResponse of(String accessToken, String loginId, String name) {
        return new LoginResponse(accessToken, null, "Bearer", loginId, name);
    }
}
