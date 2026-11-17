package com.vmticket.user;

import com.vmticket.user.entity.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps the current request's authorities ({@code ROLE_AGENT} and the like) back to {@link Role}.
 * Unknown authorities are ignored. Lives in {@code user} so account endpoints can read the caller
 * without depending on {@code security}.
 */
public final class CurrentRoles {

    private static final String ROLE_PREFIX = "ROLE_";

    private CurrentRoles() {
    }

    public static Set<Role> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(CurrentRoles::toRole)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<Role> toRole(String authority) {
        if (authority == null || authority.isBlank()) {
            return Optional.empty();
        }
        String name = authority.startsWith(ROLE_PREFIX) ? authority.substring(ROLE_PREFIX.length()) : authority;
        try {
            return Optional.of(Role.valueOf(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
