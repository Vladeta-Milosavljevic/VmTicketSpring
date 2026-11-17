package com.vmticket.config;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapSeedIntegrationTest {

    private static final String OPEN_ID = "10000000-0000-4000-8000-000000000001";
    private static final String IN_PROGRESS_ID = "10000000-0000-4000-8000-000000000002";
    private static final String PENDING_REVIEW_ID = "10000000-0000-4000-8000-000000000003";
    private static final String COMPLETED_ID = "10000000-0000-4000-8000-000000000004";
    private static final String DELETED_ID = "10000000-0000-4000-8000-000000000005";
    private static final String REJECTED_ID = "10000000-0000-4000-8000-000000000010";
    private static final String AGENT_COMMENT_ID = "20000000-0000-4000-8000-000000000002";
    private static final String ADMIN_COMMENT_ID = "20000000-0000-4000-8000-000000000003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String bearer;

    @BeforeEach
    void logInAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        bearer = "Bearer " + objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    void adminLoginReturnsAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void ticketListIncludesShowcaseAndOmitsSoftDeletedSeed() throws Exception {
        mockMvc.perform(get("/api/v1/tickets")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(49))
                .andExpect(jsonPath("$.content[*].id", hasItem(OPEN_ID)))
                .andExpect(jsonPath("$.content[*].id", hasItem(IN_PROGRESS_ID)))
                .andExpect(jsonPath("$.content[*].id", hasItem(PENDING_REVIEW_ID)))
                .andExpect(jsonPath("$.content[*].id", hasItem(COMPLETED_ID)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(DELETED_ID))));

        mockMvc.perform(get("/api/v1/tickets/{id}", DELETED_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryListOmitsArchivedFacilities() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", hasItem("Hardware")))
                .andExpect(jsonPath("$.content[*].name", hasItem("Software")))
                .andExpect(jsonPath("$.content[*].name", not(hasItem("Facilities"))));
    }

    @Test
    void inProgressTicketCommentsMatchSeed() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{id}/comments", IN_PROGRESS_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(AGENT_COMMENT_ID))
                .andExpect(jsonPath("$.content[0].author").value("agent.two"))
                .andExpect(jsonPath("$.content[0].body").value("Started diagnosis."))
                .andExpect(jsonPath("$.content[0].attachments").isEmpty())
                .andExpect(jsonPath("$.content[1].id").value(ADMIN_COMMENT_ID))
                .andExpect(jsonPath("$.content[1].author").value("admin"))
                .andExpect(jsonPath("$.content[1].body").value("Assigned priority."))
                .andExpect(jsonPath("$.content[1].attachments").isEmpty());

        mockMvc.perform(get("/api/v1/tickets/{id}/attachments", IN_PROGRESS_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectedSeedTicketHasOneRejectionRow() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{id}/rejections", REJECTED_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reason").value("Out of scope for this queue"))
                .andExpect(jsonPath("$.content[0].agent").value("agent.two"))
                .andExpect(jsonPath("$.content[0].manager").value("manager.one"));
    }
}
