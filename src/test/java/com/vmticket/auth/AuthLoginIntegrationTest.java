package com.vmticket.auth;

import tools.jackson.databind.ObjectMapper;
import com.vmticket.support.AuthTestHelper;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void savedAgentLogsInAndReceivesStoredRole() throws Exception {
        saveActiveUser("login-agent", Role.AGENT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-agent","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("login-agent"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.roles[0]").value("AGENT"));
    }

    @Test
    void loginTokenIsAcceptedOnProtectedEndpoint() throws Exception {
        saveActiveUser("login-usable", Role.AGENT);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-usable","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String bearer = "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }

    @Test
    void missingUserIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-does-not-exist","password":"password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        saveActiveUser("login-wrong", Role.AGENT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-wrong","password":"nope"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void softDeletedUserIsUnauthorized() throws Exception {
        User user = saveActiveUser("login-deleted", Role.AGENT);
        if (!user.isDeleted()) {
            user.softDelete();
            userRepository.saveAndFlush(user);
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-deleted","password":"password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDeletesTokenSoTheNextRequestIsUnauthorized() throws Exception {
        saveActiveUser("login-logout", Role.AGENT);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-logout","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String bearer = "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seededAdminIsPresentAndCanLoginOnTestProfile() throws Exception {
        assertTrue(userRepository.findByUsername("admin").isPresent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Admin","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void loginBodyUsesUsernameAndPasswordOnly() throws Exception {
        saveActiveUser("login-plain-body", Role.AGENT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"login-plain-body","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("login-plain-body"))
                .andExpect(jsonPath("$.roles[0]").value("AGENT"))
                .andExpect(jsonPath("$.roles[1]").doesNotExist());
    }

    @Test
    void sixthWrongPasswordIsTooManyRequests() throws Exception {
        saveActiveUser("login-rate-limit", Role.AGENT);
        String body = """
                {"username":"login-rate-limit","password":"nope"}
                """;

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Too many login attempts"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("login-rate-limit"))));
    }

    private User saveActiveUser(String username, Role role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User user = new User(username);
            user.setPasswordHash(passwordEncoder.encode(AuthTestHelper.TEST_PASSWORD));
            user.assignRole(role);
            return userRepository.saveAndFlush(user);
        });
    }
}
