package com.vmticket.ticket.controller;

import tools.jackson.databind.ObjectMapper;
import com.vmticket.user.entity.Role;
import com.vmticket.support.AuthTestHelper;
import com.vmticket.ticket.dto.CreateCategoryRequest;
import com.vmticket.ticket.dto.UpdateCategoryRequest;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthTestHelper authTestHelper;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentCannotCreateOrUpdate() throws Exception {
        String agentToken = authTestHelper.bearer("agent-cat", Role.AGENT);
        CreateCategoryRequest createRequest = new CreateCategoryRequest("Agent Blocked Category", null, null);

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/categories/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCategoryRequest("Renamed", null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCreatesGetsListsAndRestoresArchivedCategory() throws Exception {
        String managerToken = authTestHelper.bearer("morgan-cat", Role.MANAGER);
        String agentToken = authTestHelper.bearer("reader-cat", Role.AGENT);

        MvcResult created = mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCategoryRequest("  Printer Queue  ", "paper and toner", null))))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("Printer Queue"))
                .andExpect(jsonPath("$.slug").value("printer-queue"))
                .andExpect(jsonPath("$.description").value("paper and toner"))
                .andExpect(jsonPath("$.isArchived").value(false))
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.endsWith("/api/v1/categories/" + id));

        mockMvc.perform(get("/api/v1/categories/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.slug").value("printer-queue"));

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .param("q", "PRINTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .param("q", "printer-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].slug", hasItem("printer-queue")));

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCategoryRequest(null, null, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(true));

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));

        mockMvc.perform(get("/api/v1/categories/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(true));

        mockMvc.perform(patch("/api/v1/categories/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateCategoryRequest(null, null, null, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(false));

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .param("q", "printer-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
    }

    @Test
    void duplicateNameIsConflict() throws Exception {
        String managerToken = authTestHelper.bearer("morgan-cat", Role.MANAGER);

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCategoryRequest("Unique Billing", null, "unique-billing"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCategoryRequest("unique billing", null, "unique-billing-other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Category Conflict"));
    }

    @Test
    void blankNameIsValidationFailure() throws Exception {
        String managerToken = authTestHelper.bearer("morgan-cat", Role.MANAGER);
        String body = """
                {"name":""}
                """;

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void categoryListRejectsUnknownSort() throws Exception {
        String token = authTestHelper.bearer("reader-sort", Role.AGENT);

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("sort", "name,asc"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("sort", "description,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }
}
