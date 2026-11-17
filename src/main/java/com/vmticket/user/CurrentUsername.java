package com.vmticket.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the current request's username from the security context.
 * Lives in {@code user} so account endpoints can identify the caller without depending on {@code security}.
 */
public final class CurrentUsername {

    private CurrentUsername() {
    }

    public static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }
}
