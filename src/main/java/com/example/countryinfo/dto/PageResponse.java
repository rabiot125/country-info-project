package com.example.countryinfo.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable paging envelope; Spring's PageImpl JSON shape is not a guaranteed contract. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getSort().isSorted() ? page.getSort().toString() : null);
    }
}
