package com.ibank.domain.user.dto;

import com.ibank.domain.user.entity.User;
import com.ibank.global.security.pii.PiiMasking;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String loginId,
        String name,
        String email,
        User.UserRole role,
        LocalDateTime createdAt
) {
    /** email은 PII이므로 외부 노출 시 마스킹한다(원문 미반환). */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getLoginId(),
                user.getName(),
                PiiMasking.maskEmail(user.getEmail()),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
