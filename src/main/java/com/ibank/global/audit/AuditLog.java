package com.ibank.global.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 감사 로그. 상태 변경 행위에 대해 누가·언제·무엇을·결과·어디서를 불변 기록한다.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_actor", columnList = "actor"),
        @Index(name = "idx_audit_logs_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String actor;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(length = 100)
    private String target;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(length = 500)
    private String detail;

    @Column(length = 45)
    private String ip;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public AuditLog(String actor, String action, String target, String result, String detail, String ip) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.result = result;
        this.detail = detail;
        this.ip = ip;
        this.createdAt = LocalDateTime.now();
    }
}
