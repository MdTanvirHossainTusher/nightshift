package com.nightshift.payload.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A page of already-mapped response objects plus its {@link Pagination}, so a
 * service can return both without the controller touching Spring Data types.
 */
public record PageResult<T>(List<T> items, Pagination pagination) {

    public static <E, T> PageResult<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getContent().stream().map(mapper).toList(),
                Pagination.from(page));
    }
}
