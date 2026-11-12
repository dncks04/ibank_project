package com.ibank.internal.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 내부 API 요청의 멱등성 키와, 그 요청이 처음 성공했을 때의 응답.
 * 한 번 저장되면 바뀌지 않는다.
 */
@Entity
@Table(name = "internal_idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalIdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    // 동작·대상·본문의 해시. 같은 키가 다른 요청에 다시 쓰였는지 가려낸다.
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(length = 64)
    private String clientId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public InternalIdempotencyKey(String idempotencyKey, String requestFingerprint, String operation,
                                  String clientId, String responseBody, LocalDateTime createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.operation = operation;
        this.clientId = clientId;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }
}
