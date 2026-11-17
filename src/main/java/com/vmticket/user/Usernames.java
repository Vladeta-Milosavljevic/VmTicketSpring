package com.vmticket.user;

import java.util.Locale;

import com.vmticket.common.error.BadRequestException;

public final class Usernames {

    private Usernames() {
    }

    public static String normalize(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public static String require(String username) {
        return require(username, "username is required");
    }

    public static String require(String username, String message) {
        String normalized = normalize(username);
        if (normalized == null) {
            throw new BadRequestException(message);
        }
        return normalized;
    }
}
