package com.ibank.user;

import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.support.DatabaseCleaner;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생년월일은 개인정보다. DB에는 암호문으로만 저장되고, 엔티티로 읽으면 원래 날짜가 나와야 한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BirthDatePiiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
    }

    @Test
    @DisplayName("가입 시 받은 생년월일은 암호화되어 저장되고 엔티티로는 원래 날짜가 복원된다")
    void birthDate_encryptedAtRest() {
        LocalDate birthDate = LocalDate.of(1958, 3, 21);
        userService.register(new RegisterRequest("elderuser", "password123", "어르신", "elder@test.com", birthDate));

        String stored = jdbcTemplate.queryForObject(
                "SELECT birth_date FROM users WHERE login_id = 'elderuser'", String.class);
        assertThat(stored).isNotBlank();
        assertThat(stored).doesNotContain("1958").doesNotContain("03-21");

        User loaded = userRepository.findByLoginId("elderuser").orElseThrow();
        assertThat(loaded.getBirthDate()).isEqualTo(birthDate);
    }

    @Test
    @DisplayName("같은 생년월일도 고객마다 다른 암호문으로 저장된다 (암호문으로 생일이 같은 고객을 묶을 수 없다)")
    void sameBirthDate_differentCiphertext() {
        LocalDate birthDate = LocalDate.of(1958, 3, 21);
        userService.register(new RegisterRequest("elderone", "password123", "가", "one@test.com", birthDate));
        userService.register(new RegisterRequest("eldertwo", "password123", "나", "two@test.com", birthDate));

        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT birth_date FROM users", String.class)).hasSize(2);
    }

    @Test
    @DisplayName("생년월일 컬럼이 생기기 전에 가입한 고객은 null로 읽힌다")
    void legacyUser_hasNoBirthDate() {
        userRepository.save(User.builder()
                .loginId("legacyuser").password("x").name("기존고객")
                .email("legacy@test.com").role(User.UserRole.ROLE_USER).build());

        assertThat(userRepository.findByLoginId("legacyuser").orElseThrow().getBirthDate()).isNull();
    }
}
