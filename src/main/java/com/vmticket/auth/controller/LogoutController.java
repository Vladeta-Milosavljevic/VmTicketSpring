package com.vmticket.auth.controller;

import com.vmticket.security.service.AccessTokenService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {

    private final AccessTokenService accessTokenService;

    public LogoutController(AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the current opaque Bearer token")
    public void logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof String tokenHash) || tokenHash.isBlank()) {
            throw new BadCredentialsException("Authentication required");
        }
        accessTokenService.revoke(tokenHash);
    }
}
