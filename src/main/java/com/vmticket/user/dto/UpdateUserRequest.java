package com.vmticket.user.dto;

import com.vmticket.user.entity.Role;

public record UpdateUserRequest(Role role) {

    public boolean hasAnyUpdate() {
        return role != null;
    }
}
