package com.vmticket.ticket.service;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.config.AppStorageProperties;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.service.UserAccountService;
import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.entity.Comment;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.dto.CommentResponse;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.ticket.repository.AttachmentRepository;
import com.vmticket.ticket.repository.CommentRepository;
import com.vmticket.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private UserAccountService userAccountService;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        AppStorageProperties properties = new AppStorageProperties();
        properties.getStorage().setLocation(tempDir.toString());
        properties.getStorage().setMaxFileBytes(10L * 1024 * 1024);
        AttachmentService attachmentService = new AttachmentService(
                attachmentRepository, ticketRepository, properties);
        commentService = new CommentService(
                commentRepository,
                attachmentRepository,
                ticketRepository,
                userAccountService,
                attachmentService);
        lenient().when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attachmentRepository.save(any(Attachment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(userAccountService.requireByUsername(any())).thenAnswer(invocation -> new User(invocation.getArgument(0)));
    }

    @Test
    void assignedAgentCanCreateComment() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

        CommentResponse response = commentService.create(
                ticket.getId(), "  hello  ", List.of(), "agent1", Set.of(Role.AGENT));

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertEquals("hello", captor.getValue().getBody());
        assertEquals("agent1", captor.getValue().getAuthor().getUsername());
        assertEquals("hello", response.body());
        assertEquals("agent1", response.author());
        assertEquals(ticket.getId(), response.ticketId());
    }

    @Test
    void assignedManagerCanCreateComment() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

        CommentResponse response = commentService.create(
                ticket.getId(), "from manager", List.of(), "mgr1", Set.of(Role.MANAGER));

        assertEquals("mgr1", response.author());
        assertEquals("from manager", response.body());
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    void differentAgentAndRequesterCannotCreate() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThrows(AccessDeniedException.class, () -> commentService.create(
                ticket.getId(), "no", List.of(), "other", Set.of(Role.AGENT)));
        assertThrows(AccessDeniedException.class, () -> commentService.create(
                ticket.getId(), "no", List.of(), "req1", Set.of(Role.AGENT)));

        verify(commentRepository, never()).save(any());
        verify(userAccountService, never()).requireByUsername(any());
    }

    @Test
    void missingAuthorDoesNotSaveComment() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(userAccountService.requireByUsername("agent1"))
                .thenThrow(new BadRequestException("user not found: agent1"));

        assertThrows(BadRequestException.class, () -> commentService.create(
                ticket.getId(), "hello", List.of(), "agent1", Set.of(Role.AGENT)));

        verify(commentRepository, never()).save(any());
    }

    @Test
    void adminCanCreateWhenNobodyIsAssigned() {
        Ticket ticket = new Ticket(
                "Title", "Desc", TicketUrgency.MEDIUM, null, null, new User("req1"),
                new Category("Network", "network", null));
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

        CommentResponse response = commentService.create(
                ticket.getId(), "admin note", List.of(), "admin", Set.of(Role.ADMIN));

        assertEquals("admin", response.author());
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    void softDeletedTicketIsNotFound() {
        UUID id = UUID.randomUUID();
        when(ticketRepository.findActiveById(id)).thenReturn(Optional.empty());

        assertThrows(TicketNotFoundException.class, () -> commentService.create(
                id, "hello", List.of(), "admin", Set.of(Role.ADMIN)));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void blankBodyIsBadRequest() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThrows(BadRequestException.class, () -> commentService.create(
                ticket.getId(), "   ", List.of(), "agent1", Set.of(Role.AGENT)));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void listIsOldestFirstAndOmitsSoftDeletedAttachment() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        Comment older = new Comment(ticket, new User("agent1"), "older");
        Comment newer = new Comment(ticket, new User("mgr1"), "newer");
        Attachment visible = new Attachment(
                ticket, older, new User("agent1"), "keep.txt", "text/plain", 4, UUID.randomUUID().toString());
        Attachment deleted = new Attachment(
                ticket, older, new User("agent1"), "gone.txt", "text/plain", 4, UUID.randomUUID().toString());
        deleted.softDelete();
        when(commentRepository.findByTicket_Id(eq(ticket.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(older, newer), PageRequest.of(0, 20), 2));
        when(attachmentRepository.findByComment_IdIn(any())).thenReturn(List.of(visible, deleted));

        PageResponse<CommentResponse> response = commentService.list(
                ticket.getId(),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
                "agent1",
                Set.of(Role.AGENT));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(commentRepository).findByTicket_Id(eq(ticket.getId()), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertNotNull(order);
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertEquals(List.of(older.getId(), newer.getId()),
                response.content().stream().map(CommentResponse::id).toList());
        assertEquals(1, response.content().get(0).attachments().size());
        assertEquals(visible.getId(), response.content().get(0).attachments().get(0).id());
        assertEquals("keep.txt", response.content().get(0).attachments().get(0).originalFileName());
        assertTrue(response.content().get(1).attachments().isEmpty());
    }

    @Test
    void uploaderTicketManagerAndAdminCanRemoveFile() throws IOException {
        Ticket ticket = assignedTicket();
        Comment comment = new Comment(ticket, new User("agent1"), "note");
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(commentRepository.findByIdAndTicket_Id(comment.getId(), ticket.getId())).thenReturn(Optional.of(comment));

        assertRemoved(ticket, comment, "uploader", "uploader", Set.of(Role.AGENT));
        assertRemoved(ticket, comment, "uploader", "mgr1", Set.of(Role.MANAGER));
        assertRemoved(ticket, comment, "uploader", "root", Set.of(Role.ADMIN));
    }

    @Test
    void demotedManagerCannotRemoveSomeoneElsesFileAndDemotedUploaderCanRemoveOwn() throws IOException {
        Ticket ticket = assignedTicket();
        Comment comment = new Comment(ticket, new User("agent1"), "note");
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(commentRepository.findByIdAndTicket_Id(comment.getId(), ticket.getId())).thenReturn(Optional.of(comment));
        Attachment someoneElses = storeAttachment(ticket, comment, "uploader");

        assertThrows(AccessDeniedException.class, () -> commentService.removeAttachment(
                ticket.getId(),
                comment.getId(),
                someoneElses.getId(),
                "mgr1",
                Set.of(Role.AGENT)));
        assertFalse(someoneElses.isDeleted());
        verify(attachmentRepository, never()).save(any());

        assertRemoved(ticket, comment, "mgr1", "mgr1", Set.of(Role.AGENT));
    }

    @Test
    void otherAssignedAgentCannotRemoveFile() throws IOException {
        Ticket ticket = assignedTicket();
        Comment comment = new Comment(ticket, new User("agent1"), "note");
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(commentRepository.findByIdAndTicket_Id(comment.getId(), ticket.getId())).thenReturn(Optional.of(comment));
        Attachment attachment = storeAttachment(ticket, comment, "uploader");

        assertThrows(AccessDeniedException.class, () -> commentService.removeAttachment(
                ticket.getId(),
                comment.getId(),
                attachment.getId(),
                "agent1",
                Set.of(Role.AGENT)));

        assertFalse(attachment.isDeleted());
        assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void writtenFileIsDeletedWhenSaveFails() {
        Ticket ticket = assignedTicket();
        when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(commentRepository.save(any(Comment.class))).thenThrow(new RuntimeException("db down"));
        MockMultipartFile file = new MockMultipartFile(
                "files", "notes.txt", "text/plain", "hello".getBytes());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> commentService.create(
                ticket.getId(), "see file", List.of(file), "agent1", Set.of(Role.AGENT)));

        assertEquals("db down", ex.getMessage());
        try (var leftover = Files.walk(tempDir)) {
            assertEquals(0, leftover.filter(Files::isRegularFile).count());
        } catch (IOException ioException) {
            throw new RuntimeException(ioException);
        }
    }

    private void assertRemoved(
            Ticket ticket,
            Comment comment,
            String uploader,
            String caller,
            Set<Role> roles) throws IOException {
        Attachment attachment = storeAttachment(ticket, comment, uploader);
        TransactionSynchronizationManager.initSynchronization();
        try {
            commentService.removeAttachment(
                    ticket.getId(), comment.getId(), attachment.getId(), caller, roles);
            assertTrue(attachment.isDeleted());
            assertNotNull(attachment.getDeletedAt());
            assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));
            new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);
            assertFalse(Files.exists(tempDir.resolve(attachment.getStorageKey())));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Attachment storeAttachment(Ticket ticket, Comment comment, String uploader) throws IOException {
        String storageKey = UUID.randomUUID().toString();
        Files.writeString(tempDir.resolve(storageKey), "data");
        Attachment attachment = new Attachment(
                ticket, comment, new User(uploader), "notes.txt", "text/plain", 4, storageKey);
        when(attachmentRepository.findByIdAndComment_IdAndTicket_Id(
                attachment.getId(), comment.getId(), ticket.getId()))
                .thenReturn(Optional.of(attachment));
        return attachment;
    }

    private static Ticket assignedTicket() {
        return new Ticket(
                "Title",
                "Desc",
                TicketUrgency.MEDIUM,
                new User("agent1"),
                new User("mgr1"),
                new User("req1"),
                new Category("Network", "network", null));
    }
}
