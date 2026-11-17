package com.vmticket.user.dto;

import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        Role role,
        Instant createdAt,
        Instant deletedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRoles().iterator().next(),
                user.getCreatedAt(),
                user.getDeletedAt()
        );
    }
}
