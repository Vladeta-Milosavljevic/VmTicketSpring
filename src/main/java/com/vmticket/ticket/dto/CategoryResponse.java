package com.vmticket.ticket.dto;

import com.vmticket.ticket.entity.Category;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        String description,
        boolean isArchived,
        Instant createdAt,
        Instant updatedAt
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.isArchived(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
