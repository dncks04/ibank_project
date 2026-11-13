package com.ibank.domain.user.entity;

import com.ibank.global.security.pii.BirthDateEncryptConverter;
import com.ibank.global.security.pii.EmailEncryptConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    /**
     * PII: 생년월일. email과 같은 방식으로 at-rest 암호화 저장한다. 이 컬럼이 생기기 전에 가입한 고객은 null.
     * 나이는 저장하지 않고 필요할 때 영업 타임존의 오늘을 기준으로 계산한다.
     */
    @Convert(converter = BirthDateEncryptConverter.class)
    @Column(name = "birth_date", length = 128)
    private LocalDate birthDate;

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
    public User(String loginId, String password, String name, String email, LocalDate birthDate, UserRole role) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
        this.email = email;
        this.birthDate = birthDate;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }

    /** {@link UserPiiListener}가 저장/수정 직전 email로부터 계산한 blind index를 채운다. */
    void assignEmailBlindIndex(String emailBlindIndex) {
        this.emailBlindIndex = emailBlindIndex;
    }

    /**
     * 사용자 계정의 역할.
     *
     * <p>서비스 간 호출의 {@code ROLE_SYSTEM}은 여기에 없다. 사용자 계정이 아니라 시스템 토큰에만 붙는 권한이어서,
     * 이 enum에 넣으면 어떤 경로로든 사용자 계정에 그 역할이 저장될 여지가 생긴다.
     * (정의는 {@code JwtProvider.ROLE_SYSTEM}, 부여는 {@code JwtAuthenticationFilter}의 시스템 토큰 분기뿐이다.)
     */
    public enum UserRole {
        /** 고객 */
        ROLE_USER,
        /** 운영자 */
        ROLE_ADMIN,
        /** 창구 직원. 고객 계좌의 창구 거래를 대신 처리한다(/api/teller/**). 설정으로만 만들 수 있다. */
        ROLE_TELLER
    }
}
