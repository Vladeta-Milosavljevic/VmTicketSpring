package com.vmticket.user;

import tools.jackson.databind.ObjectMapper;
import com.vmticket.user.entity.Role;
import com.vmticket.support.AuthTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthTestHelper authTestHelper;

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nobody", Role.AGENT)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentCannotListOrDeleteUsers() throws Exception {
        String agent = authTestHelper.bearer("agent-reader", Role.AGENT);

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/users/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerListsCreatedAgentAndAdminCompletesRoleAndDelete() throws Exception {
        String manager = authTestHelper.bearer("manager-lifecycle", Role.MANAGER);
        String admin = authTestHelper.bearer("admin-lifecycle", Role.ADMIN);
        String username = "lifecycle-agent";

        MvcResult created = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, Role.AGENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .param("q", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].username").value(username))
                .andExpect(jsonPath("$.content[0].role").value("AGENT"));

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleBody(Role.MANAGER)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleBody(Role.MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleBody(Role.AGENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AGENT"));

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.deletedAt").value(notNullValue()));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("q", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("q", username)
                        .param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].deletedAt").value(notNullValue()));

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("User Not Found"));

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, Role.AGENT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("User Conflict"));
    }

    @Test
    void agentCannotCreateUser() throws Exception {
        String agent = authTestHelper.bearer("agent-caller", Role.AGENT);

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("from-agent", Role.AGENT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCreatesAgentAndCannotCreateManager() throws Exception {
        String manager = authTestHelper.bearer("manager-caller", Role.MANAGER);

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("created-agent", Role.AGENT)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/users/")))
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.username").value("created-agent"))
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()));

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("created-manager", Role.MANAGER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesManagerAndCannotCreateAdmin() throws Exception {
        String admin = authTestHelper.bearer("admin-caller", Role.ADMIN);

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("created-by-admin", Role.MANAGER)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/users/")))
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.username").value("created-by-admin"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()));

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("another-admin", Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSoftDeleteRevokesAgentToken() throws Exception {
        String agent = authTestHelper.bearer("agent-token-revoked", Role.AGENT);
        String admin = authTestHelper.bearer("admin-token-revoker", Role.ADMIN);

        MvcResult listed = mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("q", "agent-token-revoked"))
                .andExpect(status().isOk())
                .andReturn();
        String id = objectMapper.readTree(listed.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateUsernameIsConflict() throws Exception {
        String admin = authTestHelper.bearer("admin-dup", Role.ADMIN);
        String body = body("dup-user", Role.AGENT);

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("User Conflict"));
    }

    @Test
    void mixedCaseUsernameIsTheSameAccount() throws Exception {
        String admin = authTestHelper.bearer("admin-case", Role.ADMIN);

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Case.User", Role.AGENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("case.user"))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("case.user", Role.AGENT)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"CASE.USER","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("case.user"));
    }

    @Test
    void passwordChangesRevokeTokensAndHideThePassword() throws Exception {
        String admin = authTestHelper.bearer("admin-passwords", Role.ADMIN);
        String manager = authTestHelper.bearer("manager-passwords", Role.MANAGER);

        MvcResult created = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("password-agent", Role.AGENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        String agentToken = login("password-agent", "password1");

        mockMvc.perform(patch("/api/v1/users/{id}/password", id)
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"manager-cannot"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"replacement-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid credentials"));

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"password1","newPassword":"replacement-password"}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String refreshed = login("password-agent", "replacement-password");

        mockMvc.perform(patch("/api/v1/users/{id}/password", id)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"admin-reset-password"}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, refreshed))
                .andExpect(status().isUnauthorized());

        login("password-agent", "admin-reset-password");

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/v1/users/{id}/password", id)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"after-delete-password"}
                                """))
                .andExpect(status().isNotFound());

        MvcResult adminListed = mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("q", "admin-passwords"))
                .andExpect(status().isOk())
                .andReturn();
        String adminId = objectMapper.readTree(adminListed.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        mockMvc.perform(patch("/api/v1/users/{id}/password", adminId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"cannot-reset-admin"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void userListAllowsKnownSortAndRejectsPasswordSort() throws Exception {
        String admin = authTestHelper.bearer("admin-sort", Role.ADMIN);

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("sort", "username,asc"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private static String body(String username, Role role) {
        return """
                {"username":"%s","password":"password1","role":"%s"}
                """.formatted(username, role);
    }

    private static String roleBody(Role role) {
        return """
                {"role":"%s"}
                """.formatted(role);
    }
}
