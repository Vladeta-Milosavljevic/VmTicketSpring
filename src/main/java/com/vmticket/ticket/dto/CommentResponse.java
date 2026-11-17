package com.vmticket.ticket.dto;

import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.Comment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID ticketId,
        String author,
        String body,
        Instant createdAt,
        Instant updatedAt,
        List<AttachmentMetadataResponse> attachments
) {
    public static CommentResponse from(Comment comment, List<Attachment> attachments) {
        List<AttachmentMetadataResponse> visible = attachments.stream()
                .filter(attachment -> !attachment.isDeleted())
                .map(AttachmentMetadataResponse::from)
                .toList();
        return new CommentResponse(
                comment.getId(),
                comment.getTicket().getId(),
                comment.getAuthor().getUsername(),
                comment.getBody(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                visible
        );
    }
}
