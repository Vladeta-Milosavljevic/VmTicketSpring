package com.vmticket.user.controller;

import com.vmticket.common.paging.PageResponse;
import com.vmticket.user.CurrentRoles;
import com.vmticket.user.CurrentUsername;
import com.vmticket.user.dto.ChangeOwnPasswordRequest;
import com.vmticket.user.dto.ChangePasswordRequest;
import com.vmticket.user.dto.CreateUserRequest;
import com.vmticket.user.dto.UpdateUserRequest;
import com.vmticket.user.dto.UserResponse;
import com.vmticket.user.service.UserService;
import org.springframework.security.authentication.BadCredentialsException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request, CurrentRoles.current());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public UserResponse getById(@PathVariable UUID id) {
        return userService.getById(id);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @PageableDefault(size = 20, sort = "username", direction = Sort.Direction.ASC) Pageable pageable) {
        return userService.search(q, includeDeleted, pageable);
    }

    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void changeOwnPassword(@Valid @RequestBody ChangeOwnPasswordRequest request) {
        String username = CurrentUsername.current();
        if (username == null) {
            throw new BadCredentialsException("Invalid credentials");
        }
        userService.changeOwnPassword(username, request);
    }

    @PatchMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void changePassword(@PathVariable UUID id, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.changeRole(id, request, CurrentRoles.current());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        userService.softDelete(id);
    }
}
