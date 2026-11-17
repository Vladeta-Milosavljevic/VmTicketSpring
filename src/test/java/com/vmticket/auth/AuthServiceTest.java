package com.vmticket.auth;

import com.vmticket.auth.dto.LoginRequest;
import com.vmticket.auth.dto.TokenResponse;
import com.vmticket.auth.service.AuthService;
import com.vmticket.auth.service.LoginAttemptTracker;
import com.vmticket.common.error.TooManyRequestsException;
import com.vmticket.security.AppSecurityProperties;
import com.vmticket.security.service.AccessTokenService;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccessTokenService accessTokenService;

    private static final String UNKNOWN_USER_HASH = "$2a$10$unknownUserHashForTimingOnly000000000000000000";

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(UNKNOWN_USER_HASH);
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                accessTokenService,
                new LoginAttemptTracker(new AppSecurityProperties()));
    }

    @Test
    void successfulLoginIssuesToken() {
        User user = activeUser("alice", "hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(accessTokenService.issue(user.getId()))
                .thenReturn(new AccessTokenService.IssuedToken("raw-token", "alice", Set.of(Role.AGENT)));

        TokenResponse response = authService.login(new LoginRequest(" Alice ", "password"));

        assertEquals("raw-token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals("alice", response.username());
        assertEquals(Set.of(Role.AGENT), response.roles());
    }

    @Test
    void unknownUserIsCredentialsFailure() {
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("missing", "password")));

        verify(passwordEncoder).matches("password", UNKNOWN_USER_HASH);
        verify(accessTokenService, never()).issue(any());
    }

    @Test
    void deletedUserIsCredentialsFailure() {
        User user = activeUser("gone", "hash");
        user.softDelete();
        when(userRepository.findByUsername("gone")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("gone", "password")));

        verify(passwordEncoder).matches("password", UNKNOWN_USER_HASH);
        verify(accessTokenService, never()).issue(any());
    }

    @Test
    void wrongPasswordIsCredentialsFailure() {
        User user = activeUser("alice", "hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("alice", "nope")));

        verify(accessTokenService, never()).issue(any());
    }

    @Test
    void fiveFailuresBlockTheNextAttemptWithoutHashing() {
        User user = activeUser("alice", "hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> authService.login(new LoginRequest("alice", "nope")));
        }
        assertThrows(TooManyRequestsException.class,
                () -> authService.login(new LoginRequest("alice", "nope")));

        verify(passwordEncoder, times(5)).matches("nope", "hash");
        verify(accessTokenService, never()).issue(any());
    }

    @Test
    void successfulLoginResetsFailureCount() {
        User user = activeUser("alice", "hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(accessTokenService.issue(user.getId()))
                .thenReturn(new AccessTokenService.IssuedToken("raw-token", "alice", Set.of(Role.AGENT)));

        for (int i = 0; i < 4; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> authService.login(new LoginRequest("alice", "nope")));
        }
        authService.login(new LoginRequest("alice", "password"));
        for (int i = 0; i < 5; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> authService.login(new LoginRequest("alice", "nope")));
        }
        assertThrows(TooManyRequestsException.class,
                () -> authService.login(new LoginRequest("alice", "nope")));

        verify(passwordEncoder, times(9)).matches("nope", "hash");
        verify(passwordEncoder).matches("password", "hash");
    }

    @Test
    void blankPasswordHashIsCredentialsFailure() {
        User user = activeUser("alice", "   ");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("alice", "password")));

        verify(passwordEncoder, never()).matches(any(), any());
        verify(accessTokenService, never()).issue(any());
    }

    private static User activeUser(String username, String passwordHash) {
        User user = new User(username);
        user.setPasswordHash(passwordHash);
        user.assignRole(Role.AGENT);
        return user;
    }
}
