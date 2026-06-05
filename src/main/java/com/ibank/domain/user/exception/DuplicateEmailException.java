package com.ibank.domain.user.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException() {
        super(HttpStatus.BAD_REQUEST, "이미 사용 중인 이메일입니다.");
    }
}
