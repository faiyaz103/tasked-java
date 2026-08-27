package com.tasked.modular.shared.dtos;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable envelope for paginated responses, shared by every module.
 *
 * <p>Returning Spring's {@code Page} directly would serialise its internal shape — including
 * the {@code Pageable} and {@code Sort} objects — which is explicitly documented as unstable
 * across versions and would make the API contract hostage to a Spring Data upgrade. This record
 * pins the fields a client actually needs.
 *
 * @param content       the page's items, already mapped to response DTOs
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total matching rows across all pages
 * @param totalPages     total number of pages
 * @param last          whether this is the final page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    /**
     * Builds the envelope from the {@link Page} that produced it, with content mapped
     * separately.
     *
     * <p>The content is passed in rather than mapped here because the mapping usually has to
     * happen inside the service's transaction, where lazy associations are still reachable.
     */
    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
