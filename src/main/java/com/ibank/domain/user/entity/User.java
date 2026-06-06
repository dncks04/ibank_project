package com.ibank.domain.user.entity;

import com.ibank.global.security.pii.EmailEncryptConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(UserPiiListener.class)
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

    /** PII: at-rest 암호화(AES-GCM) 저장. 엔티티에는 평문으로 노출된다. 동등 조회/유니크는 {@link #emailBlindIndex} 사용. */
    @Convert(converter = EmailEncryptConverter.class)
    @Column(name = "email", nullable = false, length = 512)
    private String email;

    /** email의 blind index(HMAC). 암호문은 유니크/조회가 불가하므로 결정적 인덱스를 분리 저장한다. 리스너가 채운다. */
    @Column(name = "email_bidx", nullable = false, unique = true, length = 64)
    private String emailBlindIndex;

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

    /** {@link UserPiiListener}가 저장/수정 직전 email로부터 계산한 blind index를 채운다. */
    void assignEmailBlindIndex(String emailBlindIndex) {
        this.emailBlindIndex = emailBlindIndex;
    }

    public enum UserRole {
        ROLE_USER, ROLE_ADMIN
    }
}
