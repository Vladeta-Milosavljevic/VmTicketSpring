package com.vmticket.config;

import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AppBootstrapProperties properties;
    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        properties = new AppBootstrapProperties();
        runner = new AdminBootstrapRunner(
                userRepository, passwordEncoder, properties, inlineTransactions());
    }

    @Test
    void missingAdminAndBlankPasswordFailsStartup() {
        properties.setAdminPassword("  ");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals("BOOTSTRAP_ADMIN_PASSWORD is required to create the initial admin", ex.getMessage());

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void existingAdminIsLeftUnchanged() {
        User existing = activeAdmin("kept-hash");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(existing));
        properties.setAdminPassword("new-secret");

        runner.run(null);

        assertEquals("kept-hash", existing.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsAdminWhenMissing() {
        properties.setAdminPassword("new-secret");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("new-secret")).thenReturn("encoded");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin", saved.getUsername());
        assertEquals("encoded", saved.getPasswordHash());
        assertEquals(Set.of(Role.ADMIN), saved.getRoles());
    }

    @Test
    void deletedAdminFailsStartup() {
        User deleted = activeAdmin("kept-hash");
        deleted.softDelete();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(deleted));
        properties.setAdminPassword("new-secret");

        assertThrows(IllegalStateException.class, () -> runner.run(null));

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void nonAdminUsernameAdminFailsStartup() {
        User manager = new User("admin");
        manager.setPasswordHash("kept-hash");
        manager.assignRole(Role.MANAGER);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(manager));
        properties.setAdminPassword("new-secret");

        assertThrows(IllegalStateException.class, () -> runner.run(null));

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void shortPasswordFailsStartup() {
        properties.setAdminPassword("short");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals("BOOTSTRAP_ADMIN_PASSWORD must be between 8 and 72 characters", ex.getMessage());

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void uniqueKeyConflictThenActiveAdminReturns() {
        properties.setAdminPassword("new-secret");
        User raced = activeAdmin("kept-hash");
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        when(passwordEncoder.encode("new-secret")).thenReturn("encoded");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate username"));

        runner.run(null);

        verify(userRepository).saveAndFlush(any(User.class));
        assertEquals("kept-hash", raced.getPasswordHash());
    }

    private static User activeAdmin(String passwordHash) {
        User existing = new User("admin");
        existing.setPasswordHash(passwordHash);
        existing.assignRole(Role.ADMIN);
        return existing;
    }

    private static PlatformTransactionManager inlineTransactions() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
