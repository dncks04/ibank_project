package com.ibank.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 응답 봉투.
 *
 * @param code 기계가 읽는 오류 코드. 내부 API(서비스 간 호출)의 실패 응답에만 실린다 — 호출 서비스가 메시지 문구가
 *             아니라 이 코드로 분기한다. 값이 없으면 JSON에서 아예 빠지므로 기존 고객 API 응답의 모양은 그대로다.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message, null);
    }

    public static <T> ApiResponse<T> error(String message, String code) {
        return new ApiResponse<>(false, null, message, code);
    }
}
