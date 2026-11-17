package com.vmticket.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AppSecurityProperties.class)
public class SecurityConfig {

    private final AccessTokenAuthenticationFilter accessTokenAuthenticationFilter;
    private final AppSecurityProperties properties;

    public SecurityConfig(
            AccessTokenAuthenticationFilter accessTokenAuthenticationFilter,
            AppSecurityProperties properties) {
        this.accessTokenAuthenticationFilter = accessTokenAuthenticationFilter;
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));

        if (!properties.getSecurity().isEnabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        List<String> anonymous = new ArrayList<>();
        anonymous.add("/actuator/health");
        anonymous.add("/actuator/info");
        if (properties.getSecurity().isExposeApiDocs()) {
            anonymous.add("/v3/api-docs/**");
            anonymous.add("/swagger-ui/**");
            anonymous.add("/swagger-ui.html");
        }
        if (properties.getSecurity().isExposeH2Console()) {
            anonymous.add("/h2-console/**");
        }
        boolean allowH2Frames = properties.getSecurity().isExposeH2Console();
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(anonymous.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .headers(headers -> headers.frameOptions(frame -> {
                    if (allowH2Frames) {
                        frame.sameOrigin();
                    } else {
                        frame.deny();
                    }
                }))
                .addFilterBefore(accessTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeProblem(
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "Authentication required",
                request.getRequestURI());
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeProblem(
                response,
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "Access denied",
                request.getRequestURI());
    }

    private static void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String instance) throws java.io.IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String json = """
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","instance":"%s"}
                """.formatted(escape(title), status.value(), escape(detail), escape(instance)).trim();
        response.getWriter().write(json);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
