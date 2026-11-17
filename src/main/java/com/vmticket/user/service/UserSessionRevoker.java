package com.vmticket.user.service;

import java.util.UUID;

/**
 * Drops every session for a user. Implemented in {@code security} so account
 * deletion does not depend on access-token storage.
 */
public interface UserSessionRevoker {

    void revokeAll(UUID userId);
}
