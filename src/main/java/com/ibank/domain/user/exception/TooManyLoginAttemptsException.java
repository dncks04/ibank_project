package com.ibank.domain.user.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 로그인 실패 누적으로 일시 잠금된 상태. brute-force 방어.
 */
public class TooManyLoginAttemptsException extends BusinessException {
    public TooManyLoginAttemptsException(String loginId) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                "로그인 일시 잠금: loginId=" + loginId);
    }
}
