package com.ibank.domain.user.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 리프레시 토큰이 존재하지 않거나 만료/폐기되었음.
 */
public class InvalidRefreshTokenException extends BusinessException {
    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다.");
    }
}
