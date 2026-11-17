package com.vmticket.ticket.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.vmticket.common.error.AttachmentNotFoundException;
import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.CommentNotFoundException;
import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.ticket.dto.CommentResponse;
import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.Comment;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.repository.AttachmentRepository;
import com.vmticket.ticket.repository.CommentRepository;
import com.vmticket.ticket.repository.TicketRepository;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.service.UserAccountService;

@Service
@Transactional
public class CommentService {

    static final int MAX_BODY_LENGTH = 10000;

    private final CommentRepository commentRepository;
    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final UserAccountService userAccountService;
    private final AttachmentService attachmentService;

    public CommentService(
            CommentRepository commentRepository,
            AttachmentRepository attachmentRepository,
            TicketRepository ticketRepository,
            UserAccountService userAccountService,
            AttachmentService attachmentService) {
        this.commentRepository = commentRepository;
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.userAccountService = userAccountService;
        this.attachmentService = attachmentService;
    }

    public CommentResponse create(
            UUID ticketId,
            String body,
            List<MultipartFile> files,
            String username,
            Set<Role> roles) {
        Ticket ticket = requireActive(ticketId);
        TicketAccess.requireCanWrite(ticket, username, roles);
        String authorName = Usernames.normalize(username);
        if (authorName == null) {
            throw new BadRequestException("author is required");
        }
        String normalizedBody = normalizeBody(body);
        List<AttachmentService.PreparedFile> pendingFiles = List.of();
        List<Path> written = List.of();
        try {
            pendingFiles = attachmentService.prepare(files);
            User author = userAccountService.requireByUsername(authorName);
            Comment comment = new Comment(ticket, author, normalizedBody);
            written = attachmentService.write(pendingFiles);
            commentRepository.save(comment);
            List<Attachment> attachments = attachmentService.save(ticket, comment, author, pendingFiles);
            return CommentResponse.from(comment, attachments);
        } catch (RuntimeException ex) {
            attachmentService.deleteWritten(written);
            attachmentService.discard(pendingFiles);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(UUID ticketId, Pageable pageable, String username, Set<Role> roles) {
        requireReadable(ticketId, username, roles);
        Pageable oldestFirst = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Comment> page = commentRepository.findByTicket_Id(ticketId, oldestFirst);
        Map<UUID, List<Attachment>> attachmentsByComment = loadAttachments(page.getContent());
        Page<CommentResponse> mapped = page.map(comment -> CommentResponse.from(
                comment,
                attachmentsByComment.getOrDefault(comment.getId(), List.of())));
        return PageResponse.from(mapped);
    }

    @Transactional(readOnly = true)
    public AttachmentContent download(
            UUID ticketId, UUID commentId, UUID attachmentId, String username, Set<Role> roles) {
        requireReadable(ticketId, username, roles);
        requireComment(ticketId, commentId);
        Attachment attachment = requireVisibleAttachment(ticketId, commentId, attachmentId);
        return attachmentService.read(attachment);
    }

    public void removeAttachment(
            UUID ticketId,
            UUID commentId,
            UUID attachmentId,
            String username,
            Set<Role> roles) {
        Ticket ticket = requireActive(ticketId);
        requireComment(ticketId, commentId);
        Attachment attachment = requireVisibleAttachment(ticketId, commentId, attachmentId);
        requireCanRemove(ticket, attachment, username, roles);
        attachmentService.softDelete(attachment);
    }

    private Map<UUID, List<Attachment>> loadAttachments(List<Comment> comments) {
        if (comments.isEmpty()) {
            return Map.of();
        }
        List<UUID> commentIds = comments.stream().map(Comment::getId).toList();
        return attachmentRepository.findByComment_IdIn(commentIds).stream()
                .filter(attachment -> attachment.getComment() != null)
                .collect(Collectors.groupingBy(attachment -> attachment.getComment().getId()));
    }

    private Attachment requireVisibleAttachment(UUID ticketId, UUID commentId, UUID attachmentId) {
        return attachmentRepository.findByIdAndComment_IdAndTicket_Id(attachmentId, commentId, ticketId)
                .filter(attachment -> !attachment.isDeleted())
                .orElseThrow(() -> new AttachmentNotFoundException("Attachment not found: " + attachmentId));
    }

    private Comment requireComment(UUID ticketId, UUID commentId) {
        return commentRepository.findByIdAndTicket_Id(commentId, ticketId)
                .orElseThrow(() -> new CommentNotFoundException("Comment not found: " + commentId));
    }

    private Ticket requireActive(UUID id) {
        return ticketRepository.findActiveById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found: " + id));
    }

    private Ticket requireReadable(UUID id, String username, Set<Role> roles) {
        Ticket ticket = requireActive(id);
        TicketAccess.requireCanRead(ticket, username, roles);
        return ticket;
    }

    private static void requireCanRemove(Ticket ticket, Attachment attachment, String username, Set<Role> roles) {
        if (isAdmin(roles)) {
            return;
        }
        String caller = Usernames.normalize(username);
        if (sameUser(attachment.getUploadedBy(), caller)) {
            return;
        }
        if (sameUser(ticket.getManager(), caller) && hasRole(roles, Role.MANAGER)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static boolean isAdmin(Set<Role> roles) {
        return hasRole(roles, Role.ADMIN);
    }

    private static boolean hasRole(Set<Role> roles, Role role) {
        return roles != null && roles.contains(role);
    }

    private static boolean sameUser(User user, String username) {
        return user != null && username != null && username.equals(Usernames.normalize(user.getUsername()));
    }

    private static String normalizeBody(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequestException("body must not be blank");
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new BadRequestException("body must be at most 10000 characters");
        }
        return trimmed;
    }

}
