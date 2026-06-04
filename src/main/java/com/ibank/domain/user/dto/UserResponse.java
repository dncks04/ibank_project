package com.ibank.domain.user.dto;

import com.ibank.domain.user.entity.User;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String loginId,
        String name,
        String email,
        User.UserRole role,
        LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getLoginId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
