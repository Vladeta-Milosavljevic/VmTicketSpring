package com.vmticket.ticket.service;

import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.CategoryConflictException;
import com.vmticket.common.error.CategoryNotFoundException;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.dto.CategoryResponse;
import com.vmticket.ticket.dto.CreateCategoryRequest;
import com.vmticket.ticket.dto.UpdateCategoryRequest;
import com.vmticket.ticket.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        lenient().when(categoryRepository.saveAndFlush(any(Category.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createTrimsNameAndGeneratesSlug() {
        CreateCategoryRequest request = new CreateCategoryRequest("  Network Ops  ", "desk moves", null);

        CategoryResponse response = categoryService.create(request);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(captor.capture());
        Category saved = captor.getValue();
        assertEquals("Network Ops", saved.getName());
        assertEquals("network-ops", saved.getSlug());
        assertFalse(saved.isArchived());
        assertEquals("network-ops", response.slug());
        assertEquals("Network Ops", response.name());
    }

    @Test
    void createStoresSuppliedSlugInLowercase() {
        CreateCategoryRequest request = new CreateCategoryRequest("Billing", null, "  Billing-Team  ");

        categoryService.create(request);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(captor.capture());
        assertEquals("billing-team", captor.getValue().getSlug());
        assertEquals("Billing", captor.getValue().getName());
    }

    @Test
    void duplicateNameIsConflict() {
        when(categoryRepository.existsByNameIgnoreCase("Network Ops")).thenReturn(true);

        assertThrows(CategoryConflictException.class,
                () -> categoryService.create(new CreateCategoryRequest("Network Ops", null, null)));
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateSlugIsConflict() {
        when(categoryRepository.existsBySlugIgnoreCase("billing-team")).thenReturn(true);

        assertThrows(CategoryConflictException.class,
                () -> categoryService.create(new CreateCategoryRequest("Other", null, "Billing-Team")));
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void generatedEmptySlugIsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> categoryService.create(new CreateCategoryRequest("!!!", null, null)));
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsEmptyPatch() {
        UUID id = UUID.randomUUID();

        assertThrows(BadRequestException.class,
                () -> categoryService.update(id, new UpdateCategoryRequest(null, null, null, null)));
        verify(categoryRepository, never()).findById(any());
    }

    @Test
    void archiveThenRestore() {
        Category existing = new Category("Network Ops", "network-ops", "desc");
        UUID id = existing.getId();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(existing));

        CategoryResponse archived = categoryService.update(id, new UpdateCategoryRequest(null, null, null, true));
        assertTrue(existing.isArchived());
        assertTrue(archived.isArchived());

        CategoryResponse restored = categoryService.update(id, new UpdateCategoryRequest(null, null, null, false));
        assertFalse(existing.isArchived());
        assertFalse(restored.isArchived());
        assertEquals("network-ops", existing.getSlug());
    }

    @Test
    void getMissingIsNotFound() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(CategoryNotFoundException.class, () -> categoryService.getById(id));
    }

    @Test
    void defaultSearchHidesArchivedCategories() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("name"));
        when(categoryRepository.search(isNull(), eq(false), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        categoryService.search(null, false, pageable);

        verify(categoryRepository).search(null, false, pageable);
    }
}
