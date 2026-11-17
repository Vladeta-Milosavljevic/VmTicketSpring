package com.vmticket.ticket.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.CategoryNotFoundException;
import com.vmticket.common.error.InvalidStatusTransitionException;
import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.common.paging.SortAllowlist;
import com.vmticket.common.search.LikeQueries;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.dto.TicketResponse;
import com.vmticket.ticket.dto.UpdateTicketRequest;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.entity.Rejection;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketStatusTransitions;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.repository.CategoryRepository;
import com.vmticket.ticket.repository.RejectionRepository;
import com.vmticket.ticket.repository.TicketRepository;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.service.UserAccountService;

@Service
@Transactional
public class TicketService {

    private static final int MAX_REJECTION_REASON_LENGTH = 10000;
    private static final Set<String> SORT_PROPERTIES = Set.of(
            "createdAt", "updatedAt", "title", "status", "urgency");

    private final TicketRepository ticketRepository;
    private final UserAccountService userAccountService;
    private final AttachmentService attachmentService;
    private final CategoryRepository categoryRepository;
    private final RejectionRepository rejectionRepository;

    public TicketService(
            TicketRepository ticketRepository,
            UserAccountService userAccountService,
            AttachmentService attachmentService,
            CategoryRepository categoryRepository,
            RejectionRepository rejectionRepository) {
        this.ticketRepository = ticketRepository;
        this.userAccountService = userAccountService;
        this.attachmentService = attachmentService;
        this.categoryRepository = categoryRepository;
        this.rejectionRepository = rejectionRepository;
    }

    public TicketResponse create(CreateTicketRequest request, String requesterFromAuth, Set<Role> callerRoles) {
        return create(request, requesterFromAuth, callerRoles, List.of());
    }

    public TicketResponse create(CreateTicketRequest request, String requesterFromAuth, Set<Role> callerRoles,
            List<MultipartFile> files) {
        if (requesterFromAuth == null || requesterFromAuth.isBlank()) {
            throw new BadRequestException("requester is required");
        }
        String requesterName = requesterFromAuth.trim();
        String agentName = blankToNull(request.agent());
        requireCanSendAgentOnCreate(callerRoles, agentName);
        if (request.manager() == null || request.manager().isBlank()) {
            throw new BadRequestException("manager is required");
        }
        List<AttachmentService.PreparedFile> pending = List.of();
        List<Path> written = List.of();
        try {
            pending = files == null || files.isEmpty()
                    ? List.of()
                    : attachmentService.prepare(files);

            User requester = userAccountService.requireByUsername(requesterName);
            User manager = userAccountService.requireActiveWithRole(request.manager(), Role.MANAGER);
            User agent = agentName == null
                    ? null
                    : userAccountService.requireActiveWithRole(agentName, Role.AGENT);
            Category category = requireAssignableCategory(request.categoryId());
            Ticket ticket = new Ticket(
                    request.title().trim(),
                    request.description().trim(),
                    request.urgency(),
                    agent,
                    manager,
                    requester,
                    category);
            ticket.setDeadline(request.deadline());
            Ticket saved = ticketRepository.save(ticket);
            if (!pending.isEmpty()) {
                written = attachmentService.write(pending);
                attachmentService.save(saved, null, requester, pending);
            }
            return TicketResponse.from(saved);
        } catch (RuntimeException ex) {
            attachmentService.deleteWritten(written);
            attachmentService.discard(pending);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(UUID id, String username, Set<Role> roles) {
        Ticket ticket = requireActive(id);
        TicketAccess.requireCanRead(ticket, username, roles);
        return TicketResponse.from(ticket);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> search(
            TicketStatus status,
            TicketUrgency urgency,
            String agent,
            String manager,
            String q,
            Pageable pageable,
            String callerUsername,
            Set<Role> callerRoles) {
        SortAllowlist.requireAllowed(pageable, SORT_PROPERTIES);
        String query = likeContains(q);
        boolean isAdmin = callerRoles != null && callerRoles.contains(Role.ADMIN);
        boolean isAgent = callerRoles != null && callerRoles.contains(Role.AGENT);
        boolean isManager = callerRoles != null && callerRoles.contains(Role.MANAGER);
        Page<TicketResponse> page = ticketRepository
                .search(
                        status,
                        urgency,
                        blankToNull(agent),
                        blankToNull(manager),
                        query,
                        Usernames.normalize(callerUsername),
                        isAdmin,
                        isAgent,
                        isManager,
                        pageable)
                .map(TicketResponse::from);
        return PageResponse.from(page);
    }

    public TicketResponse update(
            UUID id, UpdateTicketRequest request, Set<Role> callerRoles, String callerUsername) {
        if (!request.hasAnyUpdate()) {
            throw new BadRequestException("At least one field must be provided for update");
        }
        if (callerUsername == null || callerUsername.isBlank()) {
            throw new BadRequestException("caller is required");
        }
        String callerName = callerUsername.trim();

        Ticket ticket = requireActive(id);
        TicketAccess.requireCanWrite(ticket, callerUsername, callerRoles);

        if (ticket.getStatus() == TicketStatus.COMPLETED) {
            throw new BadRequestException("Cannot update a completed ticket");
        }
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            throw new BadRequestException("Cannot update a cancelled ticket");
        }

        requireCanSendAssignmentOnUpdate(callerRoles, request.agent(), request.manager());
        if (request.agent() != null && request.agent().isBlank()) {
            throw new BadRequestException("agent must not be blank");
        }
        if (request.manager() != null && request.manager().isBlank()) {
            throw new BadRequestException("manager must not be blank");
        }
        if (request.title() != null && request.title().isBlank()) {
            throw new BadRequestException("title must not be blank");
        }
        if (request.description() != null && request.description().isBlank()) {
            throw new BadRequestException("description must not be blank");
        }

        if (request.version() == null || request.version() != ticket.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Ticket.class, id);
        }

        // The rejection row keeps the agent from before this patch, even when the patch reassigns them.
        User agentBeforePatch = request.status() == TicketStatus.REJECTED ? ticket.getAgent() : null;

        if (request.title() != null) {
            ticket.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            ticket.setDescription(request.description().trim());
        }
        if (request.urgency() != null) {
            ticket.setUrgency(request.urgency());
        }
        if (request.agent() != null) {
            ticket.setAgent(userAccountService.requireActiveWithRole(request.agent(), Role.AGENT));
        }
        if (request.manager() != null) {
            ticket.setManager(userAccountService.requireActiveWithRole(request.manager(), Role.MANAGER));
        }
        if (request.status() != null) {
            applyStatusTransition(
                    ticket, request.status(), request.rejectionReason(), callerRoles, callerName, agentBeforePatch);
        }
        if (request.categoryId() != null) {
            ticket.setCategory(requireAssignableCategory(request.categoryId()));
        }
        if (request.deadline() != null) {
            ticket.setDeadline(request.deadline());
        }

        return TicketResponse.from(ticketRepository.saveAndFlush(ticket));
    }

    public void softDelete(UUID id) {
        Ticket ticket = requireActive(id);
        List<String> storageKeys = attachmentService.softDeleteActiveForTicket(id);
        ticket.softDelete();
        ticketRepository.save(ticket);
        attachmentService.deleteAfterCommit(storageKeys);
    }

    private void applyStatusTransition(
            Ticket ticket,
            TicketStatus target,
            String rejectionReason,
            Set<Role> callerRoles,
            String callerUsername,
            User agentBeforePatch) {
        TicketStatus current = ticket.getStatus();
        if (current == target) {
            return;
        }
        if (!TicketStatusTransitions.canTransition(current, target)) {
            throw new InvalidStatusTransitionException(
                    "Cannot transition from " + current + " to " + target);
        }
        if (target == TicketStatus.COMPLETED && !canComplete(callerRoles)) {
            throw new AccessDeniedException("Only MANAGER or ADMIN can complete a ticket");
        }
        String trimmedReason = null;
        User rejector = null;
        if (target == TicketStatus.REJECTED) {
            if (!canReject(callerRoles)) {
                throw new AccessDeniedException("Only MANAGER or ADMIN can reject a ticket");
            }
            if (rejectionReason == null || rejectionReason.isBlank()) {
                throw new BadRequestException("rejectionReason is required when rejecting a ticket");
            }
            trimmedReason = rejectionReason.trim();
            if (trimmedReason.length() > MAX_REJECTION_REASON_LENGTH) {
                throw new BadRequestException("rejectionReason must be at most 10000 characters");
            }
            rejector = userAccountService.requireByUsername(callerUsername);
        }

        ticket.setStatus(target);
        Instant now = Instant.now();
        if (target == TicketStatus.PENDING_REVIEW) {
            ticket.setCompletedByAgentAt(now);
        } else if (target == TicketStatus.IN_PROGRESS || target == TicketStatus.OPEN) {
            ticket.setCompletedByAgentAt(null);
        }
        if (target == TicketStatus.COMPLETED && ticket.getCompletedByManagerAt() == null) {
            ticket.setCompletedByManagerAt(now);
        }
        if (target == TicketStatus.REJECTED) {
            rejectionRepository.save(new Rejection(ticket, agentBeforePatch, rejector, trimmedReason));
        }
    }

    private static boolean canComplete(Set<Role> callerRoles) {
        if (callerRoles == null) {
            return false;
        }
        return callerRoles.contains(Role.MANAGER) || callerRoles.contains(Role.ADMIN);
    }

    private static boolean canReject(Set<Role> callerRoles) {
        if (callerRoles == null) {
            return false;
        }
        return callerRoles.contains(Role.MANAGER) || callerRoles.contains(Role.ADMIN);
    }

    private Category requireAssignableCategory(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));
        if (category.isArchived()) {
            throw new BadRequestException("archived category cannot be assigned");
        }
        return category;
    }

    private Ticket requireActive(UUID id) {
        return ticketRepository.findActiveById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found: " + id));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String likeContains(String value) {
        String term = blankToNull(value);
        return term == null ? null : LikeQueries.contains(term);
    }

    private static void requireCanSendAgentOnCreate(Set<Role> callerRoles, String agent) {
        if (agent == null) {
            return;
        }
        if (canAssign(callerRoles)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static void requireCanSendAssignmentOnUpdate(Set<Role> callerRoles, String agent, String manager) {
        if (agent == null && manager == null) {
            return;
        }
        if (canAssign(callerRoles)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static boolean canAssign(Set<Role> callerRoles) {
        return callerRoles != null
                && (callerRoles.contains(Role.MANAGER) || callerRoles.contains(Role.ADMIN));
    }
}
