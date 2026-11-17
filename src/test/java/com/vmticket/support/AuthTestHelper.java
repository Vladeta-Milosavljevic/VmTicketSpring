package com.vmticket.support;

import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import com.vmticket.security.service.AccessTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthTestHelper {

    public static final String TEST_PASSWORD = "password";

    private final UserRepository userRepository;
    private final AccessTokenService accessTokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthTestHelper(
            UserRepository userRepository,
            AccessTokenService accessTokenService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.accessTokenService = accessTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Returns a bearer header for an active user. An existing active user's role is left unchanged.
     */
    @Transactional
    public String bearer(String username, Role... roles) {
        User user = userRepository.findByUsername(username)
                .filter(candidate -> !candidate.isDeleted())
                .orElseGet(() -> createUser(username, roles));
        String rawToken = accessTokenService.issue(user.getId()).rawToken();
        return "Bearer " + rawToken;
    }

    private User createUser(String username, Role... roles) {
        if (roles.length == 0) {
            throw new IllegalArgumentException("at least one role is required to create a user");
        }
        User user = new User(username);
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        user.assignRole(roles[0]);
        for (int i = 1; i < roles.length; i++) {
            user.getRoles().add(roles[i]);
        }
        return userRepository.saveAndFlush(user);
    }
}
