package com.vmticket.ticket.repository;

import com.vmticket.ticket.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsBySlugIgnoreCaseAndIdNot(String slug, UUID id);

    @Query("""
            SELECT c FROM Category c
            WHERE (:includeArchived = TRUE OR c.isArchived = FALSE)
              AND (:q IS NULL
                   OR LOWER(c.name) LIKE LOWER(:q) ESCAPE '\\'
                   OR LOWER(c.slug) LIKE LOWER(:q) ESCAPE '\\')
            """)
    Page<Category> search(
            @Param("q") String q,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);
}
