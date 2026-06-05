package com.ibank.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 비즈니스 규칙 위반을 표현하는 예외의 베이스.
 *
 * - {@code status}: 클라이언트에 반환할 HTTP 상태
 * - {@code clientMessage}: 클라이언트에 노출해도 안전한 메시지 (식별자 미포함)
 * - {@code getMessage()}: 서버 로그용 상세 메시지 (계좌번호 등 포함 가능)
 *
 * {@link GlobalExceptionHandler}가 이 타입을 일괄 처리하여, 클라이언트에는
 * {@code clientMessage}를, 로그에는 상세 메시지를 남긴다.
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String clientMessage;

    protected BusinessException(HttpStatus status, String clientMessage) {
        this(status, clientMessage, clientMessage);
    }

    protected BusinessException(HttpStatus status, String clientMessage, String logMessage) {
        super(logMessage);
        this.status = status;
        this.clientMessage = clientMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getClientMessage() {
        return clientMessage;
    }
}
