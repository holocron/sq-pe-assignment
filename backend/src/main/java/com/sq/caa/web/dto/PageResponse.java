package com.sq.caa.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Transport shape for every paged endpoint.
 *
 * <p>Spring's own {@code Page} serialisation is deliberately not exposed: it is unstable across
 * versions and leaks {@code pageable}/{@code sort} internals into the API contract. The frontend
 * only ever sees {@code content}, {@code page}, {@code size}, {@code totalElements} and
 * {@code totalPages}.
 *
 * @param content       the rows of the requested page
 * @param page          zero-based index of the returned page
 * @param size          the requested page size, not the number of rows returned
 * @param totalElements total number of rows matching the query across all pages
 * @param totalPages    total number of pages available
 * @param <T>           row type
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Wraps a repository page whose rows are already DTOs. */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(List.copyOf(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Wraps a repository page of entities, converting each row with {@code mapper}. */
    public static <S, T> PageResponse<T> of(Page<S> page, Function<? super S, ? extends T> mapper) {
        List<T> rows = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(rows, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** An empty page, used when a filter combination cannot match anything. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0L, 0);
    }
}
