package com.vmticket.config;

import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prod cannot create an ADMIN through the API, and it does not run the demo seed.
 * When enabled, create {@code admin} once. An existing active ADMIN is left unchanged, including its password.
 */
@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    static final String ADMIN_USERNAME = "admin";
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppBootstrapProperties properties;
    private final TransactionTemplate transactionTemplate;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AppBootstrapProperties properties,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            transactionTemplate.executeWithoutResult(status -> createIfMissing());
        } catch (DataIntegrityViolationException ex) {
            Boolean activeAdmin = transactionTemplate.execute(status ->
                    userRepository.findByUsername(ADMIN_USERNAME)
                            .filter(this::isActiveAdmin)
                            .isPresent());
            if (!Boolean.TRUE.equals(activeAdmin)) {
                throw ex;
            }
        }
    }

    private void createIfMissing() {
        User existing = userRepository.findByUsername(ADMIN_USERNAME).orElse(null);
        if (existing != null) {
            if (isActiveAdmin(existing)) {
                return;
            }
            throw new IllegalStateException("Cannot bootstrap admin: username admin already exists");
        }
        String password = properties.getAdminPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD is required to create the initial admin");
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must be between 8 and 72 characters");
        }
        User admin = new User(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.assignRole(Role.ADMIN);
        userRepository.saveAndFlush(admin);
    }

    private boolean isActiveAdmin(User user) {
        return !user.isDeleted() && user.getRoles().contains(Role.ADMIN);
    }
}
