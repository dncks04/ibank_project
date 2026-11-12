package com.ibank.internal.web;

/** 내부 API의 Idempotency-Key 헤더 규칙. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_LENGTH = 64;

    private IdempotencyKeys() {
    }

    /**
     * 헤더 값을 검증한다. 비어 있거나 64자를 넘으면 {@link InvalidIdempotencyKeyException}.
     * (헤더 자체가 없는 경우는 Spring이 먼저 걸러 400으로 응답한다.)
     */
    public static String validated(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
        return value;
    }

    public static class InvalidIdempotencyKeyException extends RuntimeException {
        public InvalidIdempotencyKeyException() {
            super("Idempotency-Key는 1~" + MAX_LENGTH + "자여야 합니다.");
        }
    }
}
