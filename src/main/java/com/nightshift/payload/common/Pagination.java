package com.nightshift.payload.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

@Schema(description = "Pagination metadata")
public record Pagination(
        @JsonProperty("total_count") long totalCount,
        int page,
        int size,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("has_next") boolean hasNext,
        @JsonProperty("has_prev") boolean hasPrev
) {
    public static Pagination from(Page<?> page) {
        return new Pagination(page.getTotalElements(), page.getNumber(), page.getSize(),
                page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }
}
