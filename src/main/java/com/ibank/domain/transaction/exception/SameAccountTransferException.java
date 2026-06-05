package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 동일 계좌로의 이체 시도. 출금·입금 계좌가 같으면 경제적 효과가 없는 무의미한 거래이므로 거부한다.
 * 클라이언트에는 계좌번호를 노출하지 않고 로그에만 남긴다.
 */
public class SameAccountTransferException extends BusinessException {
    public SameAccountTransferException(String accountNumber) {
        super(HttpStatus.BAD_REQUEST, "출금 계좌와 입금 계좌가 같을 수 없습니다.",
                "동일 계좌 이체 시도: " + accountNumber);
    }
}
