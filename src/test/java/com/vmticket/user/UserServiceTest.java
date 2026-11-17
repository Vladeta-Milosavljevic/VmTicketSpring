package com.vmticket.user;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.UserNotFoundException;
import com.vmticket.user.dto.ChangeOwnPasswordRequest;
import com.vmticket.user.dto.ChangePasswordRequest;
import com.vmticket.user.dto.CreateUserRequest;
import com.vmticket.user.dto.UpdateUserRequest;
import com.vmticket.user.dto.UserResponse;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import com.vmticket.user.service.UserAccountService;
import com.vmticket.user.service.UserService;
import com.vmticket.user.service.UserSessionRevoker;
import com.vmticket.common.error.UserConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String PASSWORD = "password1";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRevoker userSessionRevoker;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userSessionRevoker, passwordEncoder);
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("encoded-hash");
    }

    @Test
    void managerCreatesAnAgent() {
        UserResponse response = userService.create(
                new CreateUserRequest("  agent1  ", PASSWORD, Role.AGENT), Set.of(Role.MANAGER));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("agent1", captor.getValue().getUsername());
        assertEquals("encoded-hash", captor.getValue().getPasswordHash());
        assertEquals(Set.of(Role.AGENT), captor.getValue().getRoles());
        assertEquals("agent1", response.username());
        assertEquals(Role.AGENT, response.role());
        verify(passwordEncoder).encode(PASSWORD);
    }

    @Test
    void managerCannotCreateManagerOrAdmin() {
        assertThrows(AccessDeniedException.class, () -> userService.create(
                new CreateUserRequest("mgr2", PASSWORD, Role.MANAGER), Set.of(Role.MANAGER)));
        assertThrows(AccessDeniedException.class, () -> userService.create(
                new CreateUserRequest("root", PASSWORD, Role.ADMIN), Set.of(Role.MANAGER)));

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCreatesAgentAndManager() {
        UserResponse agent = userService.create(
                new CreateUserRequest("agent2", PASSWORD, Role.AGENT), Set.of(Role.ADMIN));
        UserResponse manager = userService.create(
                new CreateUserRequest("mgr2", PASSWORD, Role.MANAGER), Set.of(Role.ADMIN));

        assertEquals(Role.AGENT, agent.role());
        assertEquals(Role.MANAGER, manager.role());
        verify(userRepository, times(2)).saveAndFlush(any(User.class));
    }

    @Test
    void adminCannotCreateAdmin() {
        assertThrows(AccessDeniedException.class, () -> userService.create(
                new CreateUserRequest("root", PASSWORD, Role.ADMIN), Set.of(Role.ADMIN)));

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateUsernameIsConflict() {
        when(userRepository.existsByUsername("agent1")).thenReturn(true);

        assertThrows(UserConflictException.class, () -> userService.create(
                new CreateUserRequest("agent1", PASSWORD, Role.AGENT), Set.of(Role.ADMIN)));

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankUsernameIsBadRequest() {
        assertThrows(BadRequestException.class, () -> userService.create(
                new CreateUserRequest("   ", PASSWORD, Role.AGENT), Set.of(Role.ADMIN)));

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void softDeletedUsernameCannotBeReused() {
        when(userRepository.existsByUsername("retired-agent")).thenReturn(true);

        assertThrows(UserConflictException.class, () -> userService.create(
                new CreateUserRequest("retired-agent", PASSWORD, Role.AGENT), Set.of(Role.ADMIN)));

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminPromotesAgentAndDemotesManager() {
        User agent = userWithRole("agent-promote", Role.AGENT);
        User manager = userWithRole("manager-demote", Role.MANAGER);
        when(userRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));

        UserResponse promoted = userService.changeRole(
                agent.getId(), new UpdateUserRequest(Role.MANAGER), Set.of(Role.ADMIN));
        UserResponse demoted = userService.changeRole(
                manager.getId(), new UpdateUserRequest(Role.AGENT), Set.of(Role.ADMIN));

        assertEquals(Role.MANAGER, promoted.role());
        assertEquals(Set.of(Role.MANAGER), agent.getRoles());
        assertEquals(Role.AGENT, demoted.role());
        assertEquals(Set.of(Role.AGENT), manager.getRoles());
        verify(userRepository, times(2)).saveAndFlush(any(User.class));
    }

    @Test
    void managerCannotPatchRole() {
        User agent = userWithRole("agent-patch", Role.AGENT);

        assertThrows(AccessDeniedException.class, () -> userService.changeRole(
                agent.getId(), new UpdateUserRequest(Role.MANAGER), Set.of(Role.MANAGER)));

        assertEquals(Set.of(Role.AGENT), agent.getRoles());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void settingAdminOrPatchingAdminIsForbidden() {
        User agent = userWithRole("agent-role", Role.AGENT);
        User admin = userWithRole("root-role", Role.ADMIN);
        when(userRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        assertThrows(AccessDeniedException.class, () -> userService.changeRole(
                agent.getId(), new UpdateUserRequest(Role.ADMIN), Set.of(Role.ADMIN)));
        assertThrows(AccessDeniedException.class, () -> userService.changeRole(
                admin.getId(), new UpdateUserRequest(Role.AGENT), Set.of(Role.ADMIN)));

        assertEquals(Set.of(Role.AGENT), agent.getRoles());
        assertEquals(Set.of(Role.ADMIN), admin.getRoles());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void emptyRolePatchIsBadRequest() {
        assertThrows(BadRequestException.class, () -> userService.changeRole(
                UUID.randomUUID(), new UpdateUserRequest(null), Set.of(Role.ADMIN)));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminSoftDeletesAgentWithoutRemovingTheRow() {
        User agent = userWithRole("agent-delete", Role.AGENT);
        when(userRepository.findById(agent.getId())).thenReturn(Optional.of(agent));

        userService.softDelete(agent.getId());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertNotNull(captor.getValue().getDeletedAt());
        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).deleteById(any());
        verify(userSessionRevoker).revokeAll(agent.getId());
    }

    @Test
    void deletingManagerIsForbidden() {
        User manager = userWithRole("manager-delete", Role.MANAGER);
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));

        assertThrows(AccessDeniedException.class, () -> userService.softDelete(manager.getId()));

        assertNull(manager.getDeletedAt());
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).deleteById(any());
        verify(userSessionRevoker, never()).revokeAll(any());
    }

    @Test
    void requireByUsernameRejectsDeletedUser() {
        UserAccountService userAccountService = new UserAccountService(userRepository);
        User deleted = userWithRole("gone-agent", Role.AGENT);
        deleted.softDelete();
        when(userRepository.findByUsername("gone-agent")).thenReturn(Optional.of(deleted));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> userAccountService.requireByUsername(" Gone-Agent "));

        assertTrue(ex.getMessage().contains("user is not active"));

        User manager = userWithRole("morgan", Role.MANAGER);
        when(userRepository.findByUsername("morgan")).thenReturn(Optional.of(manager));
        assertEquals("morgan", userAccountService.requireActiveWithRole(" Morgan ", Role.MANAGER).getUsername());
    }

    @Test
    void mixedCaseUsernameIsStoredInLowercase() {
        UserResponse response = userService.create(
                new CreateUserRequest(" Admin ", PASSWORD, Role.AGENT), Set.of(Role.ADMIN));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("admin", captor.getValue().getUsername());
        assertEquals("admin", response.username());
    }

    @Test
    void mixedCaseDuplicateUsernameIsConflict() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThrows(UserConflictException.class, () -> userService.create(
                new CreateUserRequest(" Admin ", PASSWORD, Role.AGENT), Set.of(Role.ADMIN)));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void changeOwnPasswordRevokesSessions() {
        User user = userWithRole("alice", Role.AGENT);
        user.setPasswordHash("old-hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        userService.changeOwnPassword(" Alice ", new ChangeOwnPasswordRequest("password1", "new-password"));

        assertEquals("new-hash", user.getPasswordHash());
        verify(userSessionRevoker).revokeAll(user.getId());
    }

    @Test
    void wrongCurrentPasswordDoesNotChangeOrRevoke() {
        User user = userWithRole("alice", Role.AGENT);
        user.setPasswordHash("old-hash");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "old-hash")).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> userService.changeOwnPassword(
                "alice", new ChangeOwnPasswordRequest("nope", "new-password")));

        assertEquals("Invalid credentials", ex.getMessage());
        assertEquals("old-hash", user.getPasswordHash());
        verify(userSessionRevoker, never()).revokeAll(any());
    }

    @Test
    void adminChangesAgentPasswordAndRevokesSessions() {
        User agent = userWithRole("agent1", Role.AGENT);
        when(userRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        userService.changePassword(agent.getId(), new ChangePasswordRequest("new-password"));

        assertEquals("new-hash", agent.getPasswordHash());
        verify(userSessionRevoker).revokeAll(agent.getId());
    }

    @Test
    void adminPasswordChangeRejectsAdminMissingAndDeletedUsers() {
        User admin = userWithRole("root", Role.ADMIN);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        assertThrows(AccessDeniedException.class, () -> userService.changePassword(
                admin.getId(), new ChangePasswordRequest("new-password")));

        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> userService.changePassword(
                missing, new ChangePasswordRequest("new-password")));

        User deleted = userWithRole("gone", Role.AGENT);
        deleted.softDelete();
        when(userRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
        assertThrows(UserNotFoundException.class, () -> userService.changePassword(
                deleted.getId(), new ChangePasswordRequest("new-password")));

        verify(userSessionRevoker, never()).revokeAll(any());
        verify(passwordEncoder, never()).encode("new-password");
    }

    private static User userWithRole(String username, Role role) {
        User user = new User(username);
        user.assignRole(role);
        return user;
    }
}
