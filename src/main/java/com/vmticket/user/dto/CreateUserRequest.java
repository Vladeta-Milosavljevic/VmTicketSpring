package com.vmticket.user.dto;

import com.vmticket.user.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 120) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role
) {
}
