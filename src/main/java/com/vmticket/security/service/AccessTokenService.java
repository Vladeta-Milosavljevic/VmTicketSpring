package com.vmticket.security.service;

import com.vmticket.security.AppSecurityProperties;
import com.vmticket.security.entity.AccessToken;
import com.vmticket.security.repository.AccessTokenRepository;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AccessTokenService {

    private static final int RAW_TOKEN_BYTES = 32;

    private final AccessTokenRepository accessTokenRepository;
    private final UserRepository userRepository;
    private final AppSecurityProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccessTokenService(
            AccessTokenRepository accessTokenRepository,
            UserRepository userRepository,
            AppSecurityProperties properties) {
        this.accessTokenRepository = accessTokenRepository;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /**
     * Issues a new opaque token. The raw value is returned once and is not stored.
     */
    @Transactional
    public IssuedToken issue(UUID userId) {
        accessTokenRepository.deleteByExpiresAtLessThanEqual(Instant.now());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(properties.getAuth().getTokenTtl());
        accessTokenRepository.save(new AccessToken(user, sha256Hex(rawToken), expiresAt));
        return new IssuedToken(rawToken, user.getUsername(), Set.copyOf(user.getRoles()));
    }

    /**
     * Loads the token by the SHA-256 of the presented value and the user's current role.
     * Expired rows and tokens of a soft-deleted user are removed and do not authenticate.
     */
    @Transactional
    public Optional<AuthenticatedToken> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = sha256Hex(rawToken);
        Optional<AccessToken> found = accessTokenRepository.findByTokenHash(tokenHash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AccessToken token = found.get();
        User user = token.getUser();
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            accessTokenRepository.delete(token);
            return Optional.empty();
        }
        if (user.isDeleted()) {
            accessTokenRepository.delete(token);
            accessTokenRepository.deleteByUserId(user.getId());
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedToken(user.getUsername(), Set.copyOf(user.getRoles()), tokenHash));
    }

    @Transactional
    public void revoke(String tokenHash) {
        accessTokenRepository.deleteByTokenHash(tokenHash);
    }

    static String sha256Hex(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Raw token is omitted from {@link #toString()} so it is not written to logs by accident.
     */
    public record IssuedToken(String rawToken, String username, Set<Role> roles) {
        @Override
        public String toString() {
            return "IssuedToken[username=" + username + ", roles=" + roles + "]";
        }
    }

    public record AuthenticatedToken(String username, Set<Role> roles, String tokenHash) {
    }
}
