package com.ibank.domain.user.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class DuplicateLoginIdException extends BusinessException {
    public DuplicateLoginIdException() {
        super(HttpStatus.BAD_REQUEST, "이미 사용 중인 아이디입니다.");
    }
}
