package com.ibank.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public RefreshToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = LocalDateTime.now();
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isActive(LocalDateTime now) {
        return !revoked && expiresAt.isAfter(now);
    }
}
