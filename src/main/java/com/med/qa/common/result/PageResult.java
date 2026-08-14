package com.med.qa.common.result;

import java.util.Collections;
import java.util.List;

/**
 * Immutable page envelope shared by every paged REST endpoint.
 *
 * <p>Pages are one-based, matching what an API client sends; {@code total} is the number of rows
 * matching the query regardless of the current page, so a client can render a pager without a second
 * request. {@code totalPages} is derived at construction time instead of on every access, which keeps
 * the JSON payload self-describing for callers that cannot compute it themselves.</p>
 *
 * @param page       one-based page number, always {@code >= 1}
 * @param size       page size actually applied, always {@code >= 1}
 * @param total      number of matching rows across all pages, never negative
 * @param totalPages number of pages implied by {@code total} and {@code size}, never negative
 * @param records    content of this page, never {@code null}, unmodifiable
 * @param <T>        record type
 */
public record PageResult<T>(int page, int size, long total, int totalPages, List<T> records) {

    /**
     * Canonical constructor validating the envelope and making the content unmodifiable.
     *
     * @throws IllegalArgumentException if {@code page < 1}, {@code size < 1}, {@code total < 0},
     *                                 {@code totalPages < 0} or {@code records} is {@code null}
     */
    public PageResult {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1 but was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1 but was " + size);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative but was " + total);
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("totalPages must not be negative but was " + totalPages);
        }
        if (records == null) {
            throw new IllegalArgumentException("records must not be null");
        }
        records = List.copyOf(records);
    }

    /**
     * Builds a page, deriving {@code totalPages} from {@code total} and {@code size}.
     *
     * @param page    one-based page number, must be {@code >= 1}
     * @param size    page size actually applied, must be {@code >= 1}
     * @param total   number of matching rows across all pages, must not be negative
     * @param records content of this page, must not be {@code null}
     * @param <T>     record type
     * @return the page envelope, never {@code null}
     * @throws IllegalArgumentException if an argument violates the contract above
     */
    public static <T> PageResult<T> of(int page, int size, long total, List<T> records) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1 but was " + size);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative but was " + total);
        }
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResult<>(page, size, total, totalPages, records);
    }

    /**
     * Builds an empty page of a query that matched nothing.
     *
     * @param page one-based page number, must be {@code >= 1}
     * @param size page size actually applied, must be {@code >= 1}
     * @param <T>  record type
     * @return an empty page with {@code total == 0}
     * @throws IllegalArgumentException if {@code page < 1} or {@code size < 1}
     */
    public static <T> PageResult<T> empty(int page, int size) {
        return of(page, size, 0L, Collections.emptyList());
    }

    /**
     * Tells whether this page carries no record.
     *
     * @return {@code true} when the page content is empty
     */
    public boolean isEmpty() {
        return records.isEmpty();
    }

    /**
     * Tells whether another page follows this one.
     *
     * @return {@code true} when {@code page < totalPages}
     */
    public boolean hasNext() {
        return page < totalPages;
    }
}
