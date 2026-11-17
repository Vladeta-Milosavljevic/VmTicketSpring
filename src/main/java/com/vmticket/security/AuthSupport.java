package com.vmticket.security;

import com.vmticket.user.CurrentRoles;
import com.vmticket.user.CurrentUsername;
import com.vmticket.user.entity.Role;

import java.util.Set;

public final class AuthSupport {

    private AuthSupport() {
    }

    public static String currentUsername() {
        return CurrentUsername.current();
    }

    /**
     * Maps request authorities ({@code ROLE_AGENT} and the like) back to {@link Role}.
     * Unknown authorities are ignored.
     */
    public static Set<Role> currentRoles() {
        return CurrentRoles.current();
    }
}
