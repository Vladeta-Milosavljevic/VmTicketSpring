package com.vmticket.auth.service;

import com.vmticket.auth.dto.LoginRequest;
import com.vmticket.auth.dto.TokenResponse;
import com.vmticket.common.error.TooManyRequestsException;
import com.vmticket.security.service.AccessTokenService;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid credentials";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final String unknownUserHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService,
            LoginAttemptTracker loginAttemptTracker) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.loginAttemptTracker = loginAttemptTracker;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        String username = Usernames.normalize(request.username());
        if (username == null) {
            username = "";
        }
        if (loginAttemptTracker.isBlocked(username)) {
            throw new TooManyRequestsException("Too many login attempts");
        }
        String password = request.password() == null ? "" : request.password();
        User user = userRepository.findByUsername(username)
                .filter(candidate -> !candidate.isDeleted())
                .orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, unknownUserHash);
            loginAttemptTracker.recordFailure(username);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank()
                || !passwordEncoder.matches(password, hash)) {
            loginAttemptTracker.recordFailure(username);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        loginAttemptTracker.clear(username);
        AccessTokenService.IssuedToken issued = accessTokenService.issue(user.getId());
        return new TokenResponse(issued.rawToken(), "Bearer", issued.username(), issued.roles());
    }
}
