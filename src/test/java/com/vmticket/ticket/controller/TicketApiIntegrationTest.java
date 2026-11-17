package com.vmticket.ticket.controller;

import tools.jackson.databind.ObjectMapper;
import com.vmticket.user.dto.CreateUserRequest;
import com.vmticket.user.dto.UpdateUserRequest;
import com.vmticket.user.entity.Role;
import com.vmticket.user.repository.UserRepository;
import com.vmticket.user.service.UserService;
import com.vmticket.support.AuthTestHelper;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.dto.CreateCategoryRequest;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.dto.UpdateCategoryRequest;
import com.vmticket.ticket.dto.UpdateTicketRequest;
import com.vmticket.ticket.service.CategoryService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthTestHelper authTestHelper;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryService categoryService;

    @Test
    void createGetListPatchAndSoftDeleteHappyPath() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("bob", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String agentToken = authTestHelper.bearer("alice", Role.AGENT);
        String otherAgentToken = authTestHelper.bearer("bob", Role.AGENT);
        String managerToken = authTestHelper.bearer("morgan", Role.MANAGER);
        String adminToken = authTestHelper.bearer("carol", Role.ADMIN);
        UUID categoryId = createCategory();

        CreateTicketRequest createRequest = new CreateTicketRequest(
                "VPN down", "Cannot connect", TicketUrgency.HIGH, "alice", "morgan", categoryId, null);

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.title").value("VPN down"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.urgency").value("HIGH"))
                .andExpect(jsonPath("$.requester").value("morgan"))
                .andExpect(jsonPath("$.agent").value("alice"))
                .andExpect(jsonPath("$.manager").value("morgan"))
                .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.urgency").value("HIGH"))
                .andExpect(jsonPath("$.manager").value("morgan"))
                .andExpect(jsonPath("$.categoryId").value(categoryId.toString()));

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .param("status", "OPEN")
                        .param("manager", "morgan")
                        .param("q", "VPN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].manager").value("morgan"))
                .andExpect(jsonPath("$.page").value(0));

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, otherAgentToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"))
                .andExpect(jsonPath("$.detail").value("Ticket not found: " + id));

        UpdateTicketRequest updateRequest = new UpdateTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null, 0L);

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.agent").value("alice"))
                .andExpect(jsonPath("$.urgency").value("HIGH"))
                .andExpect(jsonPath("$.categoryId").value(categoryId.toString()));

        mockMvc.perform(delete("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, otherAgentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, otherAgentToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerCanRejectPendingReviewAndAgentCanResume() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String agentToken = authTestHelper.bearer("alice", Role.AGENT);
        String managerToken = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();

        CreateTicketRequest createRequest = new CreateTicketRequest(
                "Needs review", "Missing steps", TicketUrgency.MEDIUM, "alice", "morgan", categoryId, null);

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null,
                                ticketVersion(id, agentToken)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedByAgentAt").value(nullValue()));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.PENDING_REVIEW, null, null, null, null, null, null,
                                ticketVersion(id, agentToken)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedByAgentAt").value(notNullValue()));

        UpdateTicketRequest reject = new UpdateTicketRequest(
                null, null, TicketStatus.REJECTED, null, null, null, "missing logs", null, null,
                ticketVersion(id, managerToken));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reject)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.rejectedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", id)
                        .header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reason").value("missing logs"))
                .andExpect(jsonPath("$.content[0].manager").value("morgan"))
                .andExpect(jsonPath("$.content[0].agent").value("alice"));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null,
                                ticketVersion(id, agentToken)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedByAgentAt").value(nullValue()));

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", id)
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reason").value("missing logs"))
                .andExpect(jsonPath("$.content[0].manager").value("morgan"));
    }

    @Test
    void rejectionListRequiresAuthenticationAndActiveTicket() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        ensureUser("alice", Role.AGENT);
        String agentToken = authTestHelper.bearer("alice", Role.AGENT);

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", "10000000-0000-4000-8000-000000000005")
                        .header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void jsonCreateHasNoFilesAndMultipartCreateCanBeDownloadedAndDeleted() throws Exception {
        ensureUser("bob", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        String bob = authTestHelper.bearer("bob", Role.AGENT);
        UUID categoryId = createCategory();

        CreateTicketRequest jsonRequest = new CreateTicketRequest(
                "No files", "body", TicketUrgency.MEDIUM, null, "morgan", categoryId, null);
        MvcResult jsonCreated = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(jsonRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        String jsonId = objectMapper.readTree(jsonCreated.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments", jsonId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        byte[] payload = "hello-file".getBytes(StandardCharsets.UTF_8);
        CreateTicketRequest multipartRequest = new CreateTicketRequest(
                "With file", "body", TicketUrgency.MEDIUM, null, "morgan", categoryId, null);
        MockMultipartFile ticketPart = new MockMultipartFile(
                "ticket",
                "ticket.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(multipartRequest));
        MockMultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain", payload);

        MvcResult created = mockMvc.perform(multipart("/api/v1/tickets")
                        .file(ticketPart)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.title").value("With file"))
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments", ticketId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

        MvcResult listed = mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments", ticketId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].originalFileName").value("notes.txt"))
                .andExpect(jsonPath("$.content[0].storageKey").doesNotExist())
                .andReturn();
        String attachmentId = objectMapper.readTree(listed.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("notes.txt")))
                .andExpect(content().bytes(payload));

        mockMvc.perform(delete("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void pngSignatureMustMatchAndValidPngDownloads() throws Exception {
        ensureUser("morgan", Role.MANAGER);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();
        byte[] invalidPng = "not-a-png".getBytes(StandardCharsets.UTF_8);
        byte[] validPng = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        CreateTicketRequest blocked = new CreateTicketRequest(
                "Blocked png", "body", TicketUrgency.MEDIUM, null, "morgan", categoryId, null);
        mockMvc.perform(multipart("/api/v1/tickets")
                        .file(ticketPart(blocked))
                        .file(new MockMultipartFile("files", "photo.png", "image/png", invalidPng))
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("file type is not allowed"));

        CreateTicketRequest allowed = new CreateTicketRequest(
                "Allowed png", "body", TicketUrgency.MEDIUM, null, "morgan", categoryId, null);
        MvcResult created = mockMvc.perform(multipart("/api/v1/tickets")
                        .file(ticketPart(allowed))
                        .file(new MockMultipartFile("files", "photo.png", "image/png", validPng))
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isCreated())
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String attachmentId = firstAttachmentId(ticketId, morgan);

        mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments/{attachmentId}", ticketId, attachmentId)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("photo.png")))
                .andExpect(content().bytes(validPng));
    }

    @Test
    void disallowedUploadIsBadRequest() throws Exception {
        ensureUser("morgan", Role.MANAGER);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();
        CreateTicketRequest request = new CreateTicketRequest(
                "Blocked file", "body", TicketUrgency.MEDIUM, null, "morgan", categoryId, null);
        MockMultipartFile ticketPart = new MockMultipartFile(
                "ticket",
                "ticket.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(request));
        MockMultipartFile file = new MockMultipartFile(
                "files", "notes.exe", "application/octet-stream", "nope".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/tickets")
                        .file(ticketPart)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void validationFailureReturnsProblemDetails() throws Exception {
        String token = authTestHelper.bearer("alice", Role.AGENT);
        String body = """
                {"title":"", "description":"x"}
                """;

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.title").exists());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assigningSoftDeletedAgentCreatesNothing() throws Exception {
        ensureUser("morgan-deleted", Role.MANAGER);
        String admin = authTestHelper.bearer("admin-deleted-assign", Role.ADMIN);
        String alice = authTestHelper.bearer("alice-live", Role.AGENT);

        MvcResult createdUser = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"deleted-agent-assign","password":"password1","role":"AGENT"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = objectMapper.readTree(createdUser.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete("/api/v1/users/{id}", userId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        String title = "ticket-for-deleted-agent";
        CreateTicketRequest request = new CreateTicketRequest(
                title, "body", null, "deleted-agent-assign", "morgan-deleted", createCategory(), null);
        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("user is not active")));

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void missingCategoryIdIsValidationError() throws Exception {
        String token = authTestHelper.bearer("alice", Role.AGENT);
        String body = """
                {"title":"Needs a category","description":"x"}
                """;

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.categoryId").exists());
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        ensureUser("morgan-category", Role.MANAGER);
        String token = authTestHelper.bearer("morgan-category", Role.MANAGER);
        UUID missing = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest(
                "Unknown category", "body", TicketUrgency.LOW, null, "morgan-category", missing, null);

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Category not found: " + missing));
    }

    @Test
    void archivedCategoryCannotBeAssigned() throws Exception {
        ensureUser("morgan-archived", Role.MANAGER);
        String token = authTestHelper.bearer("morgan-archived", Role.MANAGER);
        UUID archivedId = createCategory();
        categoryService.update(archivedId, new UpdateCategoryRequest(null, null, null, true));
        CreateTicketRequest request = new CreateTicketRequest(
                "Archived category", "body", TicketUrgency.LOW, null, "morgan-archived", archivedId, null);

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("archived category cannot be assigned"));
    }

    @Test
    void patchChangesCategory() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan-patch", Role.MANAGER);
        String agent = authTestHelper.bearer("alice", Role.AGENT);
        String manager = authTestHelper.bearer("morgan-patch", Role.MANAGER);
        UUID first = createCategory();
        UUID second = createCategory();
        CreateTicketRequest createRequest = new CreateTicketRequest(
                "Change category", "body", TicketUrgency.MEDIUM, "alice", "morgan-patch", first, null);

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(first.toString()))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, null, null, null, null, null, second, null, 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(second.toString()));

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(second.toString()));
    }

    @Test
    void agentCannotSendAssignmentOnCreateOrUpdate() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String alice = authTestHelper.bearer("alice", Role.AGENT);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Agent create", "body", TicketUrgency.LOW, null, "morgan", categoryId, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agent").value(nullValue()))
                .andExpect(jsonPath("$.manager").value("morgan"))
                .andExpect(jsonPath("$.requester").value("alice"));

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Agent create with agent", "body", TicketUrgency.LOW, "alice", "morgan",
                                categoryId, null))))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Assigned", "  trimmed description  ", TicketUrgency.LOW, "alice", "morgan",
                                categoryId, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("trimmed description"))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, null, null, "alice", null, null, null, null, 0L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent").value("alice"));
    }

    @Test
    void deadlineIsSetOnCreateAndPatchAndLeftWhenOmitted() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String alice = authTestHelper.bearer("alice", Role.AGENT);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();
        Instant createdDeadline = Instant.parse("2030-01-01T12:00:00Z");
        Instant patchedDeadline = Instant.parse("2031-06-15T09:30:00Z");

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Agent deadline", "body", TicketUrgency.LOW, null, "morgan", categoryId,
                                createdDeadline))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deadline").value(createdDeadline.toString()))
                .andExpect(jsonPath("$.requester").value("alice"));

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Manager deadline", "body", TicketUrgency.LOW, "alice", "morgan", categoryId,
                                createdDeadline))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deadline").value(createdDeadline.toString()))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, null, null, null, null, null, null, patchedDeadline,
                                ticketVersion(id, alice)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").value(patchedDeadline.toString()));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                "Still has deadline", null, null, null, null, null, null, null, null,
                                ticketVersion(id, alice)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Still has deadline"))
                .andExpect(jsonPath("$.deadline").value(patchedDeadline.toString()));
    }

    @Test
    void staleTicketVersionIsConflictAndLeavesTheFirstPatch() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String alice = authTestHelper.bearer("alice", Role.AGENT);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Original title", "body", TicketUrgency.LOW, "alice", "morgan", categoryId, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                "First patch", null, null, null, null, null, null, null, null, 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First patch"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                "Stale patch", null, null, null, null, null, null, null, null, 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Ticket was updated by another request"));

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First patch"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void demotedManagerCannotPatchOrDeleteSomeoneElsesFileAndPromotedAgentCannotPatch() throws Exception {
        ensureUser("alice-slot", Role.AGENT);
        ensureUser("morgan-slot", Role.MANAGER);
        UUID categoryId = createCategory();
        String alice = authTestHelper.bearer("alice-slot", Role.AGENT);
        String morgan = authTestHelper.bearer("morgan-slot", Role.MANAGER);

        String aliceTicketId = createTicketWithFile(alice, null, "morgan-slot", categoryId, "alice-notes.txt");
        String aliceAttachmentId = firstAttachmentId(aliceTicketId, alice);
        String assignedTicketId = createTicketWithFile(
                morgan, "alice-slot", "morgan-slot", categoryId, "morgan-notes.txt");
        String morganAttachmentId = firstAttachmentId(assignedTicketId, morgan);

        UUID morganId = userRepository.findByUsername("morgan-slot").orElseThrow().getId();
        userService.changeRole(morganId, new UpdateUserRequest(Role.AGENT), Set.of(Role.ADMIN));
        String demotedMorgan = authTestHelper.bearer("morgan-slot", Role.AGENT);

        mockMvc.perform(patch("/api/v1/tickets/{id}", assignedTicketId)
                        .header(HttpHeaders.AUTHORIZATION, demotedMorgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/tickets/{ticketId}/attachments/{attachmentId}",
                        aliceTicketId, aliceAttachmentId)
                        .header(HttpHeaders.AUTHORIZATION, demotedMorgan))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/tickets/{ticketId}/attachments/{attachmentId}",
                        assignedTicketId, morganAttachmentId)
                        .header(HttpHeaders.AUTHORIZATION, demotedMorgan))
                .andExpect(status().isNoContent());

        UUID aliceId = userRepository.findByUsername("alice-slot").orElseThrow().getId();
        userService.changeRole(aliceId, new UpdateUserRequest(Role.MANAGER), Set.of(Role.ADMIN));
        String promotedAlice = authTestHelper.bearer("alice-slot", Role.MANAGER);

        mockMvc.perform(patch("/api/v1/tickets/{id}", assignedTicketId)
                        .header(HttpHeaders.AUTHORIZATION, promotedAlice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignmentMustMatchRoleAndCannotBeBlank() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        ensureUser("boss", Role.MANAGER);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();
        String title = "assignment-rules-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                title, "body", TicketUrgency.LOW, "boss", "morgan", categoryId, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("user must have role AGENT"));

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                title, "body", TicketUrgency.LOW, "missing-person", "morgan", categoryId, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("user not found")));

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                title, "body", TicketUrgency.LOW, "alice", "morgan", categoryId, null))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, "   ", null, null, null, null, null, null, null, 0L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("description must not be blank"));

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, null, null, "   ", null, null, null, null, 0L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("agent must not be blank"));

        mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent").value("alice"))
                .andExpect(jsonPath("$.manager").value("morgan"))
                .andExpect(jsonPath("$.description").value("body"));
    }

    @Test
    void rejectionKeepsTheAgentFromBeforeThePatch() throws Exception {
        ensureUser("alice", Role.AGENT);
        ensureUser("bob", Role.AGENT);
        ensureUser("morgan", Role.MANAGER);
        String alice = authTestHelper.bearer("alice", Role.AGENT);
        String morgan = authTestHelper.bearer("morgan", Role.MANAGER);
        UUID categoryId = createCategory();

        MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Reject and reassign", "body", TicketUrgency.MEDIUM, "alice", "morgan", categoryId, null))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null,
                                ticketVersion(id, alice)))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.PENDING_REVIEW, null, null, null, null, null, null,
                                ticketVersion(id, alice)))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, "bob", null, "reassigned", null, null,
                                ticketVersion(id, morgan)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent").value("bob"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", id)
                        .header(HttpHeaders.AUTHORIZATION, morgan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].agent").value("alice"))
                .andExpect(jsonPath("$.content[0].manager").value("morgan"));
    }

    @Test
    void invalidStatusAndMalformedJsonAreBadRequests() throws Exception {
        String token = authTestHelper.bearer("alice-bad-request", Role.AGENT);

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void ticketListAllowsKnownSortAndRejectsPasswordSort() throws Exception {
        String token = authTestHelper.bearer("alice-sort", Role.AGENT);

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("sort", "title,asc"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("sort", "requester.passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void percentSignInTitleIsLiteralInSearch() throws Exception {
        ensureUser("morgan-like", Role.MANAGER);
        String manager = authTestHelper.bearer("morgan-like", Role.MANAGER);
        String reader = manager;
        UUID categoryId = createCategory();
        String marker = UUID.randomUUID().toString();
        String plain = "plain-" + marker;
        String percent = "pct-" + marker + "%";

        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                plain, "body", TicketUrgency.LOW, null, "morgan-like", categoryId, null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                percent, "body", TicketUrgency.LOW, null, "morgan-like", categoryId, null))))
                .andExpect(status().isCreated());

        MvcResult all = mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, reader)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn();
        int allTickets = objectMapper.readTree(all.getResponse().getContentAsString()).get("totalElements").asInt();

        MvcResult wildcard = mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, reader)
                        .param("q", "%"))
                .andExpect(status().isOk())
                .andReturn();
        int wildcardMatches = objectMapper.readTree(wildcard.getResponse().getContentAsString())
                .get("totalElements").asInt();
        assertTrue(wildcardMatches < allTickets);

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, reader)
                        .param("q", marker + "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value(percent));
    }

    @Test
    void visibilityAndSearchCoverRequesterAgentManagerAndCategory() throws Exception {
        String admin = authTestHelper.bearer("admin", Role.ADMIN);
        String agentOne = authTestHelper.bearer("agent.one", Role.AGENT);
        String managerOne = authTestHelper.bearer("manager.one", Role.MANAGER);
        String openId = "10000000-0000-4000-8000-000000000001";
        String inProgressId = "10000000-0000-4000-8000-000000000002";
        String pendingId = "10000000-0000-4000-8000-000000000003";

        mockMvc.perform(get("/api/v1/tickets")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(openId)))
                .andExpect(jsonPath("$.content[*].id", hasItem(inProgressId)))
                .andExpect(jsonPath("$.content[*].id", hasItem(pendingId)));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, agentOne))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(openId)))
                .andExpect(jsonPath("$.content[*].id", hasItem(pendingId)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(inProgressId))));

        mockMvc.perform(get("/api/v1/tickets")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, managerOne))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(openId)))
                .andExpect(jsonPath("$.content[*].id", hasItem(inProgressId)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(pendingId))));

        mockMvc.perform(get("/api/v1/tickets/{id}", inProgressId)
                        .header(HttpHeaders.AUTHORIZATION, agentOne))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"))
                .andExpect(jsonPath("$.detail").value("Ticket not found: " + inProgressId));

        mockMvc.perform(get("/api/v1/tickets/{id}/comments", inProgressId)
                        .header(HttpHeaders.AUTHORIZATION, agentOne))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

        mockMvc.perform(get("/api/v1/tickets/{id}/attachments", inProgressId)
                        .header(HttpHeaders.AUTHORIZATION, agentOne))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", pendingId)
                        .header(HttpHeaders.AUTHORIZATION, managerOne))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

        ensureUser("morgan-search", Role.MANAGER);
        String morgan = authTestHelper.bearer("morgan-search", Role.MANAGER);
        String marker = UUID.randomUUID().toString();
        String categoryName = "SearchCat " + marker;
        UUID categoryId = categoryService.create(new CreateCategoryRequest(categoryName, null, null)).id();
        mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTicketRequest(
                                "Search title " + marker,
                                "unique-description-" + marker,
                                TicketUrgency.LOW,
                                null,
                                "morgan-search",
                                categoryId,
                                null))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .param("q", "unique-description-" + marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("unique-description-" + marker));

        mockMvc.perform(get("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, morgan)
                        .param("q", categoryName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].categoryId").value(categoryId.toString()));
    }

    private UUID createCategory() {
        return categoryService.create(new CreateCategoryRequest(
                "Ticket category " + UUID.randomUUID(), null, null)).id();
    }

    private MockMultipartFile ticketPart(CreateTicketRequest request) throws Exception {
        return new MockMultipartFile(
                "ticket",
                "ticket.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(request));
    }

    private String createTicketWithFile(
            String bearer, String agent, String manager, UUID categoryId, String fileName) throws Exception {
        CreateTicketRequest createRequest = new CreateTicketRequest(
                "Slot " + fileName, "body", TicketUrgency.LOW, agent, manager, categoryId, null);
        MockMultipartFile ticketPart = new MockMultipartFile(
                "ticket",
                "ticket.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(createRequest));
        MockMultipartFile file = new MockMultipartFile(
                "files", fileName, "text/plain", "slot-file".getBytes(StandardCharsets.UTF_8));
        MvcResult created = mockMvc.perform(multipart("/api/v1/tickets")
                        .file(ticketPart)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private long ticketVersion(String id, String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tickets/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("version").asLong();
    }

    private String firstAttachmentId(String ticketId, String bearer) throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/v1/tickets/{ticketId}/attachments", ticketId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(listed.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText();
    }

    private void ensureUser(String username, Role role) {
        if (userRepository.findByUsername(username).isEmpty()) {
            userService.create(new CreateUserRequest(username, "password1", role), Set.of(Role.ADMIN));
        }
    }
}
