package com.ibank.domain.user.service;

import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.config.StaffBootstrapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정에 적힌 직원 계정(창구 직원, 운영자)을 기동 시 만든다. 이미 있는 loginId는 건드리지 않는다
 * (재기동해도 비밀번호나 역할을 덮어쓰지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(StaffBootstrapProperties.class)
public class StaffBootstrap implements ApplicationRunner {

    private final StaffBootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        createMissingStaff();
    }

    /** @return 이번에 새로 만든 계정 수 */
    @Transactional
    public int createMissingStaff() {
        int created = 0;
        for (var entry : properties.staff().entrySet()) {
            String loginId = entry.getKey();
            StaffBootstrapProperties.Staff staff = entry.getValue();
            if (staff.role() == null || staff.role() == User.UserRole.ROLE_USER) {
                throw new IllegalStateException(
                        "ibank.bootstrap.staff." + loginId + ".role은 직원 역할(ROLE_TELLER, ROLE_ADMIN)이어야 합니다.");
            }
            if (staff.password() == null || staff.password().length() < 8) {
                throw new IllegalStateException(
                        "ibank.bootstrap.staff." + loginId + ".password는 8자 이상이어야 합니다.");
            }
            if (userRepository.existsByLoginId(loginId)) {
                continue;
            }
            userRepository.save(User.builder()
                    .loginId(loginId)
                    .password(passwordEncoder.encode(staff.password()))
                    .name(staff.name() != null ? staff.name() : loginId)
                    // 직원 계정은 연락용 이메일을 받지 않는다. 유니크 제약을 채우는 자리표시 값이다.
                    .email(loginId + "@staff.ibank.invalid")
                    .role(staff.role())
                    .build());
            created++;
            log.info("직원 계정 생성: loginId={}, role={}", loginId, staff.role());
        }
        return created;
    }
}
