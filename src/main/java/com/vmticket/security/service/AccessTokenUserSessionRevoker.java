package com.vmticket.security.service;

import com.vmticket.security.repository.AccessTokenRepository;
import com.vmticket.user.service.UserSessionRevoker;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AccessTokenUserSessionRevoker implements UserSessionRevoker {

    private final AccessTokenRepository accessTokenRepository;

    public AccessTokenUserSessionRevoker(AccessTokenRepository accessTokenRepository) {
        this.accessTokenRepository = accessTokenRepository;
    }

    @Override
    public void revokeAll(UUID userId) {
        accessTokenRepository.deleteByUserId(userId);
    }
}
