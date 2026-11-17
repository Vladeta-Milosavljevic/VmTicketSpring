package com.vmticket.ticket.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.CategoryNotFoundException;
import com.vmticket.common.error.InvalidStatusTransitionException;
import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.dto.TicketResponse;
import com.vmticket.ticket.dto.UpdateTicketRequest;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.entity.Rejection;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.repository.CategoryRepository;
import com.vmticket.ticket.repository.RejectionRepository;
import com.vmticket.ticket.repository.TicketRepository;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.service.UserAccountService;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

        @Mock
        private TicketRepository ticketRepository;

        @Mock
        private UserAccountService userAccountService;

        @Mock
        private AttachmentService attachmentService;

        @Mock
        private CategoryRepository categoryRepository;

        @Mock
        private RejectionRepository rejectionRepository;

        @InjectMocks
        private TicketService ticketService;

        private Ticket existing;
        private Category category;

        @BeforeEach
        void setUp() {
                category = new Category("Network", "network", null);
                existing = new Ticket(
                                "Title",
                                "Desc",
                                TicketUrgency.MEDIUM,
                                new User("agent1"),
                                new User("mgr1"),
                                new User("req1"),
                                category);
                lenient().when(ticketRepository.save(any(Ticket.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                lenient().when(ticketRepository.saveAndFlush(any(Ticket.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                lenient().when(userAccountService.requireByUsername(any()))
                                .thenAnswer(invocation -> new User(invocation.getArgument(0)));
                lenient().when(userAccountService.requireActiveWithRole(any(), any()))
                                .thenAnswer(invocation -> new User(invocation.getArgument(0)));
                lenient().when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        }

        @Test
        void createStoresAuthRequesterAndDefaultsUrgencyToMedium() {
                CreateTicketRequest request = new CreateTicketRequest(
                                "  New ticket  ", "  body  ", null, "a", "m", category.getId(), null);

                TicketResponse response = ticketService.create(request, "jwt-user", Set.of(Role.MANAGER));

                ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
                verify(ticketRepository).save(captor.capture());
                Ticket saved = captor.getValue();

                assertEquals("New ticket", saved.getTitle());
                assertEquals("body", saved.getDescription());
                assertEquals("a", saved.getAgent().getUsername());
                assertEquals("m", saved.getManager().getUsername());
                assertEquals("jwt-user", saved.getRequester().getUsername());
                assertNotNull(saved.getRequester().getId());
                assertEquals(TicketUrgency.MEDIUM, saved.getUrgency());
                assertEquals(category.getId(), saved.getCategory().getId());
                assertEquals(TicketStatus.OPEN, response.status());
                assertEquals("jwt-user", response.requester());
                assertEquals(TicketUrgency.MEDIUM, response.urgency());
                assertEquals(category.getId(), response.categoryId());
                assertNull(response.deadline());
        }

        @Test
        void createPersistsDeadline() {
                Instant deadline = Instant.parse("2030-01-01T12:00:00Z");
                CreateTicketRequest request = new CreateTicketRequest(
                                "New ticket", "body", null, "a", "m", category.getId(), deadline);

                TicketResponse response = ticketService.create(request, "jwt-user", Set.of(Role.MANAGER));

                assertEquals(deadline, response.deadline());
                ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
                verify(ticketRepository).save(captor.capture());
                assertEquals(deadline, captor.getValue().getDeadline());
        }

        @Test
        void agentCanCreateWithDeadline() {
                Instant deadline = Instant.parse("2030-06-01T08:00:00Z");
                CreateTicketRequest request = new CreateTicketRequest(
                                "Agent deadline", "body", null, null, "m", category.getId(), deadline);

                TicketResponse response = ticketService.create(request, "jwt-user", Set.of(Role.AGENT));

                assertEquals(deadline, response.deadline());
                assertNull(response.agent());
        }

        @Test
        void createRejectsMissingAuthenticatedUsername() {
                CreateTicketRequest request = new CreateTicketRequest("New ticket", "body", null, "a", "m",
                                category.getId(), null);

                assertThrows(BadRequestException.class, () -> ticketService.create(request, null, Set.of(Role.AGENT)));
                assertThrows(BadRequestException.class, () -> ticketService.create(request, "  ", Set.of(Role.AGENT)));
                verify(userAccountService, never()).requireByUsername(any());
                verify(userAccountService, never()).requireActiveWithRole(any(), any());
        }

        @Test
        void blankAgentStaysUnassignedAndManagerIsRequired() {
                CreateTicketRequest blankAgent = new CreateTicketRequest(
                                "New ticket", "body", null, "  ", "m", category.getId(), null);

                TicketResponse response = ticketService.create(blankAgent, "jwt-user", Set.of(Role.MANAGER));

                assertNull(response.agent());
                assertEquals("m", response.manager());
                assertEquals("jwt-user", response.requester());

                CreateTicketRequest missingManager = new CreateTicketRequest(
                                "New ticket", "body", null, null, "  ", category.getId(), null);
                BadRequestException ex = assertThrows(BadRequestException.class,
                                () -> ticketService.create(missingManager, "jwt-user", Set.of(Role.ADMIN)));
                assertEquals("manager is required", ex.getMessage());
        }

        @Test
        void agentCallerCannotSendAgentOrManager() {
                CreateTicketRequest withManager = new CreateTicketRequest(
                                "New ticket", "body", null, null, "m", category.getId(), null);
                TicketResponse created = ticketService.create(withManager, "jwt-user", Set.of(Role.AGENT));
                assertNull(created.agent());
                assertEquals("m", created.manager());
                verify(ticketRepository).save(any());

                CreateTicketRequest withAgent = new CreateTicketRequest(
                                "New ticket", "body", null, "a", "m", category.getId(), null);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.create(withAgent, "jwt-user", Set.of(Role.AGENT)));
                verify(ticketRepository, times(1)).save(any());
        }

        @Test
        void missingRequesterAgentOrManagerDoesNotSaveTicket() {
                when(userAccountService.requireActiveWithRole("ghost", Role.AGENT))
                                .thenThrow(new BadRequestException("user not found: ghost"));
                when(userAccountService.requireActiveWithRole("ghost", Role.MANAGER))
                                .thenThrow(new BadRequestException("user not found: ghost"));
                CreateTicketRequest missingAgent = new CreateTicketRequest(
                                "New ticket", "body", null, "ghost", "m", category.getId(), null);
                CreateTicketRequest missingManager = new CreateTicketRequest(
                                "New ticket", "body", null, null, "ghost", category.getId(), null);
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(missingAgent, "jwt-user", Set.of(Role.MANAGER)));
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(missingManager, "jwt-user", Set.of(Role.ADMIN)));

                CreateTicketRequest agentSendsManager = new CreateTicketRequest(
                                "New ticket", "body", null, null, "ghost", category.getId(), null);
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(agentSendsManager, "jwt-user", Set.of(Role.AGENT)));

                when(userAccountService.requireByUsername("jwt-user"))
                                .thenThrow(new BadRequestException("user not found: jwt-user"));
                CreateTicketRequest missingRequester = new CreateTicketRequest(
                                "New ticket", "body", null, null, "m", category.getId(), null);
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(missingRequester, "jwt-user", Set.of(Role.MANAGER)));

                verify(ticketRepository, never()).save(any());
        }

        @Test
        void updateRejectsEmptyPatch() {
                UUID id = existing.getId();

                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id,
                                                new UpdateTicketRequest(null, null, null, null, null, null, null, null, null, 0L),
                                                Set.of(), "agent1"));
        }

        @Test
        void updateRejectsIllegalTransition() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.COMPLETED, null, null, null, null, null, null, 0L);
                assertThrows(InvalidStatusTransitionException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "agent1"));
                assertEquals(TicketStatus.OPEN, existing.getStatus());
        }

        @Test
        void agentCannotCompleteTicket() {
                UUID id = existing.getId();
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.COMPLETED, null, null, null, null, null, null, 0L);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "agent1"));
                assertEquals(TicketStatus.PENDING_REVIEW, existing.getStatus());
        }

        @Test
        void managerCanRejectPendingReviewTicket() {
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setManager(new User("rejecting.manager"));
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, "agent2", null, "  needs more detail  ", null, null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.MANAGER), "rejecting.manager");

                ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
                verify(ticketRepository).saveAndFlush(ticketCaptor.capture());
                assertEquals(TicketStatus.REJECTED, ticketCaptor.getValue().getStatus());
                assertEquals(TicketStatus.REJECTED, response.status());
                assertEquals("agent2", response.agent());

                ArgumentCaptor<Rejection> rejectionCaptor = ArgumentCaptor.forClass(Rejection.class);
                verify(rejectionRepository).save(rejectionCaptor.capture());
                Rejection saved = rejectionCaptor.getValue();
                assertEquals("needs more detail", saved.getReason());
                assertEquals("agent1", saved.getAgent().getUsername());
                assertEquals("rejecting.manager", saved.getManager().getUsername());
                assertEquals(id, saved.getTicket().getId());
        }

        @Test
        void agentCannotRejectPendingReviewTicket() {
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "needs more detail", null, null, 0L);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "agent1"));
                assertEquals(TicketStatus.PENDING_REVIEW, existing.getStatus());
                verify(ticketRepository, never()).save(any());
                verify(rejectionRepository, never()).save(any());
        }

        @Test
        void rejectingPendingReviewWithoutReasonIsBadRequest() {
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setManager(new User("rejecting.manager"));
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "  ", null, null, 0L);
                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "rejecting.manager"));
                assertEquals(TicketStatus.PENDING_REVIEW, existing.getStatus());
                verify(ticketRepository, never()).save(any());
                verify(rejectionRepository, never()).save(any());
        }

        @Test
        void rejectingWithReasonLongerThan10000IsBadRequest() {
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setManager(new User("rejecting.manager"));
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "x".repeat(10001), null, null, 0L);
                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "rejecting.manager"));
                assertEquals(TicketStatus.PENDING_REVIEW, existing.getStatus());
                verify(ticketRepository, never()).save(any());
                verify(rejectionRepository, never()).save(any());
        }

        @Test
        void openToRejectedIsIllegalTransition() {
                existing.setManager(new User("rejecting.manager"));
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "not ready", null, null, 0L);
                assertThrows(InvalidStatusTransitionException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "rejecting.manager"));
                assertEquals(TicketStatus.OPEN, existing.getStatus());
                verify(rejectionRepository, never()).save(any());
        }

        @Test
        void rejectedTicketCanReturnToInProgress() {
                existing.setStatus(TicketStatus.REJECTED);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, "ignored", null, null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.AGENT), "agent1");

                assertEquals(TicketStatus.IN_PROGRESS, response.status());
                assertEquals(TicketStatus.IN_PROGRESS, existing.getStatus());
                verifyNoInteractions(rejectionRepository);
        }

        @Test
        void laterRejectionAppendsASecondRowWithoutChangingTheFirst() {
                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setManager(new User("rejecting.manager"));
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "first reason", null, null, 0L),
                                Set.of(Role.MANAGER), "rejecting.manager");
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.PENDING_REVIEW, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "second reason", null, null, 0L),
                                Set.of(Role.MANAGER), "rejecting.manager");

                ArgumentCaptor<Rejection> captor = ArgumentCaptor.forClass(Rejection.class);
                verify(rejectionRepository, times(2)).save(captor.capture());
                Rejection first = captor.getAllValues().get(0);
                Rejection second = captor.getAllValues().get(1);
                assertEquals("first reason", first.getReason());
                assertEquals("second reason", second.getReason());
                assertEquals("agent1", first.getAgent().getUsername());
                assertEquals("rejecting.manager", first.getManager().getUsername());
        }

        @Test
        void updateRejectsBlankCaller() {
                UUID id = existing.getId();
                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L);

                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), null));
                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "  "));
                assertEquals("Title", existing.getTitle());
                assertEquals(TicketStatus.OPEN, existing.getStatus());
                verify(ticketRepository, never()).save(any());
                verifyNoInteractions(rejectionRepository);
        }

        @Test
        void pendingReviewOverwritesCompletedByAgentAt() {
                existing.setStatus(TicketStatus.IN_PROGRESS);
                Instant seeded = Instant.parse("2020-01-01T00:00:00Z");
                existing.setCompletedByAgentAt(seeded);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, TicketStatus.PENDING_REVIEW, null, null, null, null, null, null, 0L);
                ticketService.update(id, request, Set.of(Role.AGENT), "agent1");

                ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
                verify(ticketRepository).saveAndFlush(captor.capture());
                Instant savedAt = captor.getValue().getCompletedByAgentAt();
                assertNotNull(savedAt);
                assertTrue(savedAt.isAfter(seeded));
        }

        @Test
        void softDeleteMarksDeletedAt() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                when(attachmentService.softDeleteActiveForTicket(id)).thenReturn(List.of("stored-key"));

                ticketService.softDelete(id);

                ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
                verify(ticketRepository).save(captor.capture());
                assertTrue(captor.getValue().isDeleted());
                assertNotNull(captor.getValue().getDeletedAt());
                verify(attachmentService).softDeleteActiveForTicket(id);
                verify(attachmentService).deleteAfterCommit(List.of("stored-key"));
        }

        @Test
        void getByIdThrowsWhenMissing() {
                UUID id = UUID.randomUUID();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.empty());

                assertThrows(TicketNotFoundException.class,
                                () -> ticketService.getById(id, "agent1", Set.of(Role.AGENT)));
        }

        @Test
        void getByIdHidesTicketFromDemotedManager() {
                UUID id = existing.getId();
                existing.setManager(new User("was-manager"));
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                TicketNotFoundException ex = assertThrows(TicketNotFoundException.class,
                                () -> ticketService.getById(id, "was-manager", Set.of(Role.AGENT)));
                assertEquals("Ticket not found: " + id, ex.getMessage());
        }

        @Test
        void createRejectsUnknownCategory() {
                UUID missing = UUID.randomUUID();
                when(categoryRepository.findById(missing)).thenReturn(Optional.empty());
                CreateTicketRequest request = new CreateTicketRequest(
                                "New ticket", "body", null, "a", "m", missing, null);

                CategoryNotFoundException ex = assertThrows(
                                CategoryNotFoundException.class,
                                () -> ticketService.create(request, "jwt-user", Set.of(Role.MANAGER)));

                assertEquals("Category not found: " + missing, ex.getMessage());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void createRejectsArchivedCategory() {
                category.setArchived(true);
                CreateTicketRequest request = new CreateTicketRequest(
                                "New ticket", "body", null, "a", "m", category.getId(), null);

                BadRequestException ex = assertThrows(
                                BadRequestException.class,
                                () -> ticketService.create(request, "jwt-user", Set.of(Role.MANAGER)));

                assertEquals("archived category cannot be assigned", ex.getMessage());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void updateChangesCategory() {
                Category next = new Category("Hardware", "hardware", null);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                when(categoryRepository.findById(next.getId())).thenReturn(Optional.of(next));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, null, null, null, null, null, next.getId(), null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.AGENT), "agent1");

                assertEquals(next.getId(), existing.getCategory().getId());
                assertEquals(next.getId(), response.categoryId());
        }

        @Test
        void updateRejectsArchivedCategory() {
                Category archived = new Category("Legacy", "legacy", null);
                archived.setArchived(true);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                when(categoryRepository.findById(archived.getId())).thenReturn(Optional.of(archived));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                null, null, null, null, null, null, null, archived.getId(), null, 0L);
                BadRequestException ex = assertThrows(
                                BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "agent1"));

                assertEquals("archived category cannot be assigned", ex.getMessage());
                assertEquals(category.getId(), existing.getCategory().getId());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void updateOmittingCategoryLeavesIt() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.AGENT), "agent1");

                assertEquals("Renamed", response.title());
                assertEquals(category.getId(), existing.getCategory().getId());
                assertEquals(category.getId(), response.categoryId());
        }

        @Test
        void completedTicketCannotBeUpdated() {
                existing.setStatus(TicketStatus.COMPLETED);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L);
                BadRequestException ex = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "mgr1"));
                assertEquals("Cannot update a completed ticket", ex.getMessage());
                assertEquals("Title", existing.getTitle());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void cancelledTicketCannotBeUpdated() {
                existing.setStatus(TicketStatus.CANCELLED);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, TicketStatus.IN_PROGRESS, null, "agent1", "mgr1", null, null, null, 0L);
                BadRequestException ex = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "mgr1"));
                assertEquals("Cannot update a cancelled ticket", ex.getMessage());
                assertEquals("Title", existing.getTitle());
                assertEquals(TicketStatus.CANCELLED, existing.getStatus());
                verify(ticketRepository, never()).save(any());
                verify(userAccountService, never()).requireActiveWithRole(any(), any());
        }

        @Test
        void rejectedTicketCanBePatched() {
                existing.setStatus(TicketStatus.REJECTED);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed rejected", null, TicketStatus.CANCELLED, null, null, null, null, null, null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.MANAGER), "mgr1");

                assertEquals("Renamed rejected", response.title());
                assertEquals(TicketStatus.CANCELLED, response.status());
        }

        @Test
        void demotedManagerCannotPatchAndPromotedAgentCannotPatch() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, request, Set.of(Role.AGENT), "mgr1"));
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, request, Set.of(Role.MANAGER), "agent1"));
                assertEquals("Title", existing.getTitle());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void mixedCaseAssignedCallerCanPatch() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest request = new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L);
                TicketResponse response = ticketService.update(id, request, Set.of(Role.AGENT), " Agent1 ");

                assertEquals("Renamed", response.title());
        }

        @Test
        void assignedAgentCannotSendAgentOrManagerEvenWhenUnchanged() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest sameAgent = new UpdateTicketRequest(
                                null, null, null, null, "agent1", null, null, null, null, 0L);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, sameAgent, Set.of(Role.AGENT), "agent1"));

                UpdateTicketRequest sameManager = new UpdateTicketRequest(
                                null, null, null, null, null, "mgr1", null, null, null, 0L);
                assertThrows(AccessDeniedException.class,
                                () -> ticketService.update(id, sameManager, Set.of(Role.AGENT), "agent1"));

                assertEquals("agent1", existing.getAgent().getUsername());
                assertEquals("mgr1", existing.getManager().getUsername());
                verify(userAccountService, never()).requireActiveWithRole(any(), any());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void blankAssignmentOnUpdateIsBadRequestAndDoesNotClear() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest blankAgent = new UpdateTicketRequest(
                                null, null, null, null, "  ", null, null, null, null, 0L);
                BadRequestException agentEx = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, blankAgent, Set.of(Role.MANAGER), "mgr1"));
                assertEquals("agent must not be blank", agentEx.getMessage());

                UpdateTicketRequest blankManager = new UpdateTicketRequest(
                                null, null, null, null, null, "  ", null, null, null, 0L);
                BadRequestException managerEx = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, blankManager, Set.of(Role.ADMIN), "admin"));
                assertEquals("manager must not be blank", managerEx.getMessage());

                assertEquals("agent1", existing.getAgent().getUsername());
                assertEquals("mgr1", existing.getManager().getUsername());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void wrongRoleOrMissingAssigneeIsBadRequest() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                when(userAccountService.requireActiveWithRole("boss", Role.AGENT))
                                .thenThrow(new BadRequestException("user must have role AGENT"));
                when(userAccountService.requireActiveWithRole("gone", Role.AGENT))
                                .thenThrow(new BadRequestException("user is not active: gone"));

                UpdateTicketRequest wrongRole = new UpdateTicketRequest(
                                null, null, null, null, "boss", null, null, null, null, 0L);
                BadRequestException roleEx = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, wrongRole, Set.of(Role.MANAGER), "mgr1"));
                assertEquals("user must have role AGENT", roleEx.getMessage());

                UpdateTicketRequest deleted = new UpdateTicketRequest(
                                null, null, null, null, "gone", null, null, null, null, 0L);
                assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, deleted, Set.of(Role.MANAGER), "mgr1"));

                assertEquals("agent1", existing.getAgent().getUsername());
                verify(ticketRepository, never()).save(any());
        }

        @Test
        void blankDescriptionIsRejectedAndPresentDescriptionIsTrimmed() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                UpdateTicketRequest blank = new UpdateTicketRequest(
                                null, "   ", null, null, null, null, null, null, null, 0L);
                BadRequestException ex = assertThrows(BadRequestException.class,
                                () -> ticketService.update(id, blank, Set.of(Role.AGENT), "agent1"));
                assertEquals("description must not be blank", ex.getMessage());
                assertEquals("Desc", existing.getDescription());

                UpdateTicketRequest trimmed = new UpdateTicketRequest(
                                null, "  more detail  ", null, null, null, null, null, null, null, 0L);
                TicketResponse response = ticketService.update(id, trimmed, Set.of(Role.AGENT), "agent1");
                assertEquals("more detail", response.description());
                assertEquals("more detail", existing.getDescription());
        }

        @Test
        void openAndInProgressClearCompletedByAgentAt() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                Instant seeded = Instant.parse("2020-01-01T00:00:00Z");

                existing.setStatus(TicketStatus.IN_PROGRESS);
                existing.setCompletedByAgentAt(seeded);
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.OPEN, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                assertNull(existing.getCompletedByAgentAt());

                existing.setStatus(TicketStatus.OPEN);
                existing.setCompletedByAgentAt(seeded);
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                assertNull(existing.getCompletedByAgentAt());
        }

        @Test
        void completedRejectedAndCancelledKeepCompletedByAgentAt() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));
                Instant seeded = Instant.parse("2020-01-01T00:00:00Z");
                existing.setManager(new User("rejecting.manager"));

                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setCompletedByAgentAt(seeded);
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.COMPLETED, null, null, null, null, null, null, 0L),
                                Set.of(Role.MANAGER), "rejecting.manager");
                assertEquals(seeded, existing.getCompletedByAgentAt());

                existing.setStatus(TicketStatus.PENDING_REVIEW);
                existing.setCompletedByAgentAt(seeded);
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.REJECTED, null, null, null, "not ready", null, null, 0L),
                                Set.of(Role.MANAGER), "rejecting.manager");
                assertEquals(seeded, existing.getCompletedByAgentAt());

                existing.setStatus(TicketStatus.IN_PROGRESS);
                existing.setCompletedByAgentAt(seeded);
                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.CANCELLED, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                assertEquals(seeded, existing.getCompletedByAgentAt());
        }

        @Test
        void unchangedStatusDoesNotClearCompletedByAgentAt() {
                existing.setStatus(TicketStatus.IN_PROGRESS);
                Instant seeded = Instant.parse("2020-01-01T00:00:00Z");
                existing.setCompletedByAgentAt(seeded);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");

                assertEquals(seeded, existing.getCompletedByAgentAt());
                verifyNoInteractions(rejectionRepository);
        }

        @Test
        void returningToInProgressClearsCompletedByAgentAt() {
                existing.setStatus(TicketStatus.REJECTED);
                Instant seeded = Instant.parse("2020-01-01T00:00:00Z");
                existing.setCompletedByAgentAt(seeded);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                ticketService.update(id, new UpdateTicketRequest(
                                null, null, TicketStatus.IN_PROGRESS, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");

                assertNull(existing.getCompletedByAgentAt());
        }

        @Test
        void updateSetsDeadlineAndOmittingItLeavesTheValue() {
                Instant original = Instant.parse("2030-01-01T12:00:00Z");
                Instant next = Instant.parse("2031-01-01T12:00:00Z");
                existing.setDeadline(original);
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                ticketService.update(id, new UpdateTicketRequest(
                                "Renamed", null, null, null, null, null, null, null, null, 0L),
                                Set.of(Role.AGENT), "agent1");
                assertEquals(original, existing.getDeadline());
                assertEquals("Renamed", existing.getTitle());

                TicketResponse response = ticketService.update(id, new UpdateTicketRequest(
                                null, null, null, null, null, null, null, null, next, 0L),
                                Set.of(Role.AGENT), "agent1");
                assertEquals(next, response.deadline());
                assertEquals(next, existing.getDeadline());
        }

        @Test
        void updateRejectsStaleVersion() {
                UUID id = existing.getId();
                when(ticketRepository.findActiveById(id)).thenReturn(Optional.of(existing));

                assertThrows(ObjectOptimisticLockingFailureException.class,
                                () -> ticketService.update(id, new UpdateTicketRequest(
                                                "Renamed", null, null, null, null, null, null, null, null, 5L),
                                                Set.of(Role.AGENT), "agent1"));
                assertEquals("Title", existing.getTitle());
        }
}
