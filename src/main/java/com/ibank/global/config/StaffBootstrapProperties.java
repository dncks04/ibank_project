package com.ibank.global.config;

import com.ibank.domain.user.entity.User;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 기동 시 만들어 둘 직원 계정(창구 직원, 운영자).
 *
 * <p>회원가입 API는 언제나 고객(ROLE_USER) 계정만 만든다. 직원 계정을 API로 만들 수 있게 하면 권한 상승 경로가
 * 되므로, 직원 계정은 설정으로만 만든다. 키가 loginId다.
 * <pre>
 * ibank.bootstrap.staff.teller01.password=...
 * ibank.bootstrap.staff.teller01.role=ROLE_TELLER
 * ibank.bootstrap.staff.teller01.name=창구직원
 * </pre>
 * 비밀번호가 설정 파일에 들어가므로 개발·시연 환경용이다. 운영에서는 환경변수로 주입하고, 최초 기동 뒤 비운다
 * (이미 있는 계정은 건드리지 않으므로 비워도 계정은 남는다).
 */
@ConfigurationProperties(prefix = "ibank.bootstrap")
public record StaffBootstrapProperties(Map<String, Staff> staff) {

    public StaffBootstrapProperties {
        staff = staff == null ? Map.of() : Map.copyOf(staff);
    }

    public record Staff(String password, User.UserRole role, String name) {}
}
