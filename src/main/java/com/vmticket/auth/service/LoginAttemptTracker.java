package com.vmticket.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.vmticket.security.AppSecurityProperties;

@Component
public class LoginAttemptTracker {

    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final AppSecurityProperties properties;

    public LoginAttemptTracker(AppSecurityProperties properties) {
        this.properties = properties;
    }

    public boolean isBlocked(String username) {
        Window window = failures.get(username);
        if (window == null) {
            return false;
        }
        Instant now = Instant.now();
        if (expired(window, now)) {
            failures.remove(username, window);
            return false;
        }
        return window.count() >= properties.getAuth().getLoginMaxFailures();
    }

    public void recordFailure(String username) {
        Instant now = Instant.now();
        Duration limit = properties.getAuth().getLoginWindow();
        failures.compute(username, (key, current) -> {
            if (current == null || now.isAfter(current.windowStart().plus(limit))) {
                return new Window(1, now);
            }
            return new Window(current.count() + 1, current.windowStart());
        });
    }

    public void clear(String username) {
        failures.remove(username);
    }

    private boolean expired(Window window, Instant now) {
        return now.isAfter(window.windowStart().plus(properties.getAuth().getLoginWindow()));
    }

    private record Window(int count, Instant windowStart) {
    }
}
