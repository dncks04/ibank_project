package com.ibank.user;

import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.UserResponse;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.exception.DuplicateEmailException;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.security.pii.PiiMasking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * email PII 보호 검증: at-rest 암호화 + blind index + 마스킹.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EmailPiiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("email은 DB에 암호문으로 저장되고(blind index 동반) 엔티티 조회 시 평문으로 복호화된다")
    void email_storedEncrypted_readDecrypted() {
        String plain = "alice@example.com";
        userService.register(new RegisterRequest("aliceuser", "password123", "앨리스", plain));

        // DB 원본 컬럼은 평문이 아니어야 한다(at-rest 암호화)
        String rawEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE login_id = ?", String.class, "aliceuser");
        assertThat(rawEmail).isNotNull().isNotEqualTo(plain).doesNotContain("example.com");

        // blind index는 64자리 hex로 채워진다
        String rawBidx = jdbcTemplate.queryForObject(
                "SELECT email_bidx FROM users WHERE login_id = ?", String.class, "aliceuser");
        assertThat(rawBidx).isNotNull().matches("[0-9a-f]{64}");

        // 엔티티로 읽으면 평문으로 복호화된다(round-trip)
        User loaded = userRepository.findByLoginId("aliceuser").orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo(plain);
    }

    @Test
    @DisplayName("대소문자/공백만 다른 동일 email은 blind index로 중복 탐지된다")
    void duplicateEmail_detectedViaBlindIndex_caseInsensitive() {
        userService.register(new RegisterRequest("firstuser", "password123", "첫번째", "Bob@Example.com"));

        assertThatThrownBy(() -> userService.register(
                new RegisterRequest("seconduser", "password123", "두번째", "  bob@example.com ")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    @DisplayName("회원가입 응답의 email은 마스킹되어 원문이 노출되지 않는다")
    void registerResponse_masksEmail() {
        UserResponse response = userService.register(
                new RegisterRequest("caroluser", "password123", "캐롤", "carol@example.com"));

        assertThat(response.email()).isEqualTo("ca***@e******.com").doesNotContain("carol@example.com");
    }

    @Test
    @DisplayName("마스킹 규칙: 로컬part 앞 2자 + 도메인 첫 글자만 남기고 TLD 보존")
    void maskEmail_format() {
        assertThat(PiiMasking.maskEmail("test@test.com")).isEqualTo("te**@t***.com");
        assertThat(PiiMasking.maskEmail("a@b.io")).isEqualTo("*@*.io");
        assertThat(PiiMasking.maskEmail(null)).isNull();
        assertThat(PiiMasking.maskEmail("")).isEmpty();
    }
}
