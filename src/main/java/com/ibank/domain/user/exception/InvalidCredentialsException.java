package com.ibank.domain.user.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 로그인 자격증명 불일치. 아이디/비밀번호 어느 쪽이 틀렸는지 구분하지 않는다(계정 열거 방지).
 */
public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super(HttpStatus.BAD_REQUEST, "아이디 또는 비밀번호가 올바르지 않습니다.");
    }
}
