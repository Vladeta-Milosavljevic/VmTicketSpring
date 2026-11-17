package com.vmticket.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.vmticket.common.error.BadRequestException;

class UsernamesTest {

    @Test
    void normalizeTrimsAndLowercases() {
        assertEquals("alice", Usernames.normalize(" Alice "));
        assertEquals("admin", Usernames.normalize("ADMIN"));
    }

    @Test
    void normalizeBlankOrNullReturnsNull() {
        assertNull(Usernames.normalize(null));
        assertNull(Usernames.normalize(""));
        assertNull(Usernames.normalize("   "));
    }

    @Test
    void requireUsesDefaultMessage() {
        assertEquals("alice", Usernames.require(" Alice "));
        BadRequestException ex = assertThrows(BadRequestException.class, () -> Usernames.require("  "));
        assertEquals("username is required", ex.getMessage());
        assertThrows(BadRequestException.class, () -> Usernames.require(null));
    }

    @Test
    void requireKeepsCallSiteMessage() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> Usernames.require("  ", "username must not be blank"));
        assertEquals("username must not be blank", ex.getMessage());
    }
}
