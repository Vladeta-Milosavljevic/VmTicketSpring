package com.vmticket.ticket.dto;

import com.vmticket.ticket.entity.Attachment;

import java.util.UUID;

public record AttachmentMetadataResponse(
        UUID id,
        String originalFileName,
        String contentType,
        long sizeBytes
) {
    public static AttachmentMetadataResponse from(Attachment attachment) {
        return new AttachmentMetadataResponse(
                attachment.getId(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes()
        );
    }
}
