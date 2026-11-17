package com.vmticket.ticket.controller;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vmticket.support.AuthTestHelper;
import com.vmticket.ticket.dto.CreateCategoryRequest;
import com.vmticket.ticket.dto.CreateCommentRequest;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.service.CategoryService;
import com.vmticket.user.dto.CreateUserRequest;
import com.vmticket.user.entity.Role;
import com.vmticket.user.repository.UserRepository;
import com.vmticket.user.service.UserService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommentApiIntegrationTest {

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
        void unauthenticatedRequestsAreRejected() throws Exception {
                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments", UUID.randomUUID()))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void unassignedAgentIsForbiddenAndAssignedAgentCanCreate() throws Exception {
                ensureUser("alice", Role.AGENT);
                ensureUser("bob", Role.AGENT);
                ensureUser("morgan", Role.MANAGER);
                String alice = authTestHelper.bearer("alice", Role.AGENT);
                String bob = authTestHelper.bearer("bob", Role.AGENT);
                String cara = authTestHelper.bearer("cara", Role.MANAGER);
                String ticketId = createTicket("alice", "morgan");

                mockMvc.perform(post("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, bob)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {"body":"not allowed"}
                                                """))
                                .andExpect(status().isForbidden());

                MvcResult created = mockMvc.perform(post("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, alice)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {"body":"hello"}
                                                """))
                                .andExpect(status().isCreated())
                                .andExpect(header().string(HttpHeaders.LOCATION, containsString(
                                                "/api/v1/tickets/" + ticketId + "/comments/")))
                                .andExpect(jsonPath("$.author").value("alice"))
                                .andExpect(jsonPath("$.body").value("hello"))
                                .andExpect(jsonPath("$.ticketId").value(ticketId))
                                .andExpect(jsonPath("$.isInternal").doesNotExist())
                                .andExpect(jsonPath("$.attachments").isEmpty())
                                .andReturn();

                String commentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, cara))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].id").value(commentId))
                                .andExpect(jsonPath("$.content[0].author").value("alice"))
                                .andExpect(jsonPath("$.content[0].body").value("hello"))
                                .andExpect(jsonPath("$.content[0].isInternal").doesNotExist());
        }

        @Test
        void multipartCreateStoresFileAndDeleteHidesIt() throws Exception {
                ensureUser("alice-files", Role.AGENT);
                ensureUser("morgan-files", Role.MANAGER);
                String alice = authTestHelper.bearer("alice-files", Role.AGENT);
                String bob = authTestHelper.bearer("bob-files", Role.AGENT);
                String ticketId = createTicket("alice-files", "morgan-files");
                byte[] payload = "hello-file".getBytes(StandardCharsets.UTF_8);
                MockMultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain", payload);

                CreateCommentRequest commentRequest = new CreateCommentRequest("see file");
                MockMultipartFile commentFile = new MockMultipartFile("comment", "comment.json",
                                MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(commentRequest));

                MvcResult created = mockMvc.perform(multipart("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .file(commentFile)
                                .file(file)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.body").value("see file"))
                                .andExpect(jsonPath("$.attachments[0].originalFileName").value("notes.txt"))
                                .andExpect(jsonPath("$.attachments[0].contentType").value("text/plain"))
                                .andExpect(jsonPath("$.attachments[0].sizeBytes").value(payload.length))
                                .andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist())
                                .andReturn();

                var comment = objectMapper.readTree(created.getResponse().getContentAsString());
                String commentId = comment.get("id").asText();
                String attachmentId = comment.get("attachments").get(0).get("id").asText();

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}",
                                ticketId, commentId, attachmentId)
                                .header(HttpHeaders.AUTHORIZATION, bob))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.title").value("Ticket Not Found"));

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}",
                                ticketId, commentId, attachmentId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                                                containsString("notes.txt")))
                                .andExpect(content().bytes(payload));

                mockMvc.perform(delete("/api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}",
                                ticketId, commentId, attachmentId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isNoContent());

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments/{commentId}/attachments/{attachmentId}",
                                ticketId, commentId, attachmentId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.title").value("Attachment Not Found"));

                mockMvc.perform(get("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].id").value(commentId))
                                .andExpect(jsonPath("$.content[0].attachments").isEmpty());
        }

        @Test
        void commentHasNoEditOrDeleteRoute() throws Exception {
                String alice = authTestHelper.bearer("alice-routes", Role.AGENT);
                String ticketId = UUID.randomUUID().toString();

                mockMvc.perform(patch("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, alice)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isMethodNotAllowed());

                mockMvc.perform(delete("/api/v1/tickets/{ticketId}/comments", ticketId)
                                .header(HttpHeaders.AUTHORIZATION, alice))
                                .andExpect(status().isMethodNotAllowed());
        }

        private String createTicket(String agent, String manager) throws Exception {
                ensureUser(agent, Role.AGENT);
                ensureUser(manager, Role.MANAGER);
                String managerToken = authTestHelper.bearer(manager, Role.MANAGER);
                UUID categoryId = categoryService.create(new CreateCategoryRequest(
                                "Comment category " + UUID.randomUUID(), null, null)).id();
                CreateTicketRequest request = new CreateTicketRequest(
                                "Comment ticket", "details", TicketUrgency.MEDIUM, agent, manager, categoryId, null);
                MvcResult created = mockMvc.perform(post("/api/v1/tickets")
                                .header(HttpHeaders.AUTHORIZATION, managerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();
                return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        }

        private void ensureUser(String username, Role role) {
                if (userRepository.findByUsername(username).isEmpty()) {
                        userService.create(new CreateUserRequest(username, "password1", role), Set.of(Role.ADMIN));
                }
        }
}
