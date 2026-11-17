package com.vmticket.ticket.service;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.CategoryConflictException;
import com.vmticket.common.error.CategoryNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.common.paging.SortAllowlist;
import com.vmticket.common.search.LikeQueries;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.dto.CategoryResponse;
import com.vmticket.ticket.dto.CreateCategoryRequest;
import com.vmticket.ticket.dto.UpdateCategoryRequest;
import com.vmticket.ticket.repository.CategoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class CategoryService {

    private static final int SLUG_MAX_LENGTH = 140;
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> SORT_PROPERTIES = Set.of("name", "slug");

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public CategoryResponse create(CreateCategoryRequest request) {
        String name = request.name().trim();
        String slug = request.slug() == null ? slugFromName(name) : normalizeSuppliedSlug(request.slug());
        assertUnique(name, slug, null);
        Category category = new Category(name, slug, request.description());
        return CategoryResponse.from(save(category));
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(UUID id) {
        return CategoryResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> search(String q, boolean includeArchived, Pageable pageable) {
        SortAllowlist.requireAllowed(pageable, SORT_PROPERTIES);
        Page<CategoryResponse> page = categoryRepository
                .search(likeContains(q), includeArchived, pageable)
                .map(CategoryResponse::from);
        return PageResponse.from(page);
    }

    public CategoryResponse update(UUID id, UpdateCategoryRequest request) {
        if (!request.hasAnyUpdate()) {
            throw new BadRequestException("At least one field must be provided for update");
        }

        Category category = require(id);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("name must not be blank");
            }
            category.setName(request.name().trim());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }
        if (request.slug() != null) {
            category.setSlug(normalizeSuppliedSlug(request.slug()));
        }
        if (request.isArchived() != null) {
            category.setArchived(request.isArchived());
        }
        assertUnique(category.getName(), category.getSlug(), category.getId());
        return CategoryResponse.from(save(category));
    }

    private void assertUnique(String name, String slug, UUID excludeId) {
        boolean nameClash = excludeId == null
                ? categoryRepository.existsByNameIgnoreCase(name)
                : categoryRepository.existsByNameIgnoreCaseAndIdNot(name, excludeId);
        if (nameClash) {
            throw new CategoryConflictException("Category name already exists");
        }
        boolean slugClash = excludeId == null
                ? categoryRepository.existsBySlugIgnoreCase(slug)
                : categoryRepository.existsBySlugIgnoreCaseAndIdNot(slug, excludeId);
        if (slugClash) {
            throw new CategoryConflictException("Category slug already exists");
        }
    }

    private Category save(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            throw new CategoryConflictException("Category name or slug already exists");
        }
    }

    private Category require(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + id));
    }

    private static String slugFromName(String trimmedName) {
        String slug = trimmedName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty() || slug.length() > SLUG_MAX_LENGTH || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BadRequestException("slug could not be generated from name");
        }
        return slug;
    }

    private static String normalizeSuppliedSlug(String slug) {
        String normalized = slug.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > SLUG_MAX_LENGTH || !SLUG_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "slug must match ^[a-z0-9]+(?:-[a-z0-9]+)*$ and be at most 140 characters");
        }
        return normalized;
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
}
