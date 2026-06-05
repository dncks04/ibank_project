package com.ibank.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String loginId;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /**
     * access 토큰 무효화 버전. 토큰 발급 시 이 값을 클레임에 박고 요청마다 일치하는지 검증한다.
     * 증가시키면 기존 access 토큰이 즉시 무효화된다(stateless JWT의 즉시 차단 경로).
     */
    @Column(nullable = false)
    private long tokenVersion;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String loginId, String password, String name, String email, UserRole role) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
        this.email = email;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }

    public enum UserRole {
        ROLE_USER, ROLE_ADMIN
    }
}
