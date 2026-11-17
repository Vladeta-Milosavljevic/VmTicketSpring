package com.vmticket.auth.dto;

import com.vmticket.user.entity.Role;

import java.util.Set;

public record TokenResponse(String accessToken, String tokenType, String username, Set<Role> roles) {
}
