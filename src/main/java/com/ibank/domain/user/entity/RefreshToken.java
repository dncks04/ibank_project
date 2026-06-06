package com.ibank.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 리프레시 토큰. 원문은 저장하지 않고 SHA-256 해시만 보관한다.
 * 로그인 시 발급, /refresh 시 회전(기존 폐기 + 신규 발급), /logout 시 폐기.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** 회전(rotation)으로 폐기되었는지. true인 토큰의 재제출은 탈취 의심(재사용 탐지 대상). */
    @Column(nullable = false)
    private boolean rotated;

    /** 회전된 시각. 회전 직후 유예창 안의 재제출(정상 동시 재시도)과 진짜 재사용을 구분하는 기준. */
    @Column
    private LocalDateTime rotatedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.rotated = false;
        this.createdAt = LocalDateTime.now();
    }

    /** 로그아웃 등 일반 폐기. 재사용 탐지 대상이 아니다(rotated=false 유지). */
    public void revoke() {
        this.revoked = true;
    }

    /** 회전으로 소비됨. 이후 재제출되면 탈취로 간주한다. */
    public void markRotated() {
        this.revoked = true;
        this.rotated = true;
        this.rotatedAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * 회전 직후 유예창 안의 재제출인지. true이면 탈취가 아니라 정상 동시 재시도(클라이언트 재시도/더블클릭)로 보고
     * 전체 세션 무효화 없이 단순 거부한다. {@code rotatedAt}이 없는(과거) 토큰은 유예창에 들지 않는다.
     */
    public boolean isWithinReuseLeeway(LocalDateTime now, Duration leeway) {
        return rotatedAt != null && !now.isAfter(rotatedAt.plus(leeway));
    }
}
