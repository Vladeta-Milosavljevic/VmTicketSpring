package com.vmticket.user.service;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserRepository userRepository;

    public UserAccountService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        String normalized = Usernames.require(username);
        User user = userRepository.findByUsername(normalized)
                .orElseThrow(() -> new BadRequestException("user not found: " + normalized));
        if (user.isDeleted()) {
            throw new BadRequestException("user is not active: " + normalized);
        }
        return user;
    }

    /**
     * Active user who holds {@code role}. Missing and soft-deleted users use the same 400s as
     * {@link #requireByUsername(String)}. A different role is also 400.
     */
    @Transactional(readOnly = true)
    public User requireActiveWithRole(String username, Role role) {
        if (role == null) {
            throw new BadRequestException("role is required");
        }
        User user = requireByUsername(username);
        if (!user.getRoles().contains(role)) {
            throw new BadRequestException("user must have role " + role);
        }
        return user;
    }
}
