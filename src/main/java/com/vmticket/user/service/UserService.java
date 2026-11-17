package com.vmticket.user.service;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.UserConflictException;
import com.vmticket.common.error.UserNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.common.paging.SortAllowlist;
import com.vmticket.common.search.LikeQueries;
import com.vmticket.user.Usernames;
import com.vmticket.user.dto.ChangeOwnPasswordRequest;
import com.vmticket.user.dto.ChangePasswordRequest;
import com.vmticket.user.dto.CreateUserRequest;
import com.vmticket.user.dto.UpdateUserRequest;
import com.vmticket.user.dto.UserResponse;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private static final int USERNAME_MAX_LENGTH = 120;
    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final Set<String> SORT_PROPERTIES = Set.of("username", "createdAt");

    private final UserRepository userRepository;
    private final UserSessionRevoker userSessionRevoker;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            UserSessionRevoker userSessionRevoker,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userSessionRevoker = userSessionRevoker;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse create(CreateUserRequest request, Set<Role> callerRoles) {
        String username = requireNormalizedUsername(request.username());
        if (request.role() == null) {
            throw new BadRequestException("role is required");
        }
        requireCanAssign(callerRoles, request.role());
        if (userRepository.existsByUsername(username)) {
            throw new UserConflictException("username already exists");
        }
        User user = new User(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.assignRole(request.role());
        return UserResponse.from(save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return UserResponse.from(requirePresent(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String q, boolean includeDeleted, Pageable pageable) {
        SortAllowlist.requireAllowed(pageable, SORT_PROPERTIES);
        Page<UserResponse> page = userRepository
                .search(likeContains(q), includeDeleted, pageable)
                .map(UserResponse::from);
        return PageResponse.from(page);
    }

    public UserResponse changeRole(UUID id, UpdateUserRequest request, Set<Role> callerRoles) {
        if (callerRoles == null || !callerRoles.contains(Role.ADMIN)) {
            throw new AccessDeniedException("Access denied");
        }
        if (!request.hasAnyUpdate()) {
            throw new BadRequestException("At least one field must be provided for update");
        }

        User user = requireActive(id);
        Role current = currentRole(user);
        Role next = request.role();
        if (current == Role.ADMIN || next == Role.ADMIN) {
            throw new AccessDeniedException("Access denied");
        }
        if (current == next) {
            return UserResponse.from(user);
        }
        if (!isAgentManagerSwap(current, next)) {
            throw new AccessDeniedException("Access denied");
        }
        user.assignRole(next);
        return UserResponse.from(save(user));
    }

    public void changeOwnPassword(String username, ChangeOwnPasswordRequest request) {
        User user = userRepository.findByUsername(requireNormalizedUsername(username))
                .filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(request.currentPassword(), hash)) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        replacePassword(user, request.newPassword());
    }

    public void changePassword(UUID id, ChangePasswordRequest request) {
        User user = requireActive(id);
        Role role = currentRole(user);
        if (role != Role.AGENT && role != Role.MANAGER) {
            throw new AccessDeniedException("Access denied");
        }
        replacePassword(user, request.newPassword());
    }

    public void softDelete(UUID id) {
        User user = requirePresent(id);
        if (user.isDeleted()) {
            throw new UserNotFoundException("User not found: " + id);
        }
        if (currentRole(user) != Role.AGENT) {
            throw new AccessDeniedException("Access denied");
        }
        userSessionRevoker.revokeAll(user.getId());
        user.softDelete();
        save(user);
    }

    private static boolean isAgentManagerSwap(Role current, Role next) {
        return (current == Role.AGENT && next == Role.MANAGER)
                || (current == Role.MANAGER && next == Role.AGENT);
    }

    private static Role currentRole(User user) {
        return user.getRoles().iterator().next();
    }

    private User requireActive(UUID id) {
        User user = requirePresent(id);
        if (user.isDeleted()) {
            throw new UserNotFoundException("User not found: " + id);
        }
        return user;
    }

    private User requirePresent(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String likeContains(String value) {
        String term = blankToNull(value);
        return term == null ? null : LikeQueries.contains(term);
    }

    private static void requireCanAssign(Set<Role> callerRoles, Role role) {
        if (callerRoles != null && callerRoles.contains(Role.ADMIN)) {
            if (role == Role.AGENT || role == Role.MANAGER) {
                return;
            }
            throw new AccessDeniedException("Access denied");
        }
        if (callerRoles != null && callerRoles.contains(Role.MANAGER) && role == Role.AGENT) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static String requireNormalizedUsername(String username) {
        String normalized = Usernames.require(username, "username must not be blank");
        if (normalized.length() > USERNAME_MAX_LENGTH) {
            throw new BadRequestException("username must be at most 120 characters");
        }
        return normalized;
    }

    private void replacePassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        save(user);
        userSessionRevoker.revokeAll(user.getId());
    }

    private User save(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new UserConflictException("username already exists");
        }
    }
}
