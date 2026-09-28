package com.xammer.cloud.dto.matters;

import java.util.List;

/**
 * A page of results.
 *
 * <p>Note {@link #page} is <b>1-based</b>, matching the Matters API rather than Spring Data's 0-based
 * {@code Page}. Kept in the vendor's terms on purpose: this is a pass-through proxy, and converting the
 * index at our boundary would mean two off-by-one conversions — ours on the way in, ours again on the
 * way out — for no benefit. The frontend's existing XamSecure pager is 0-based, so the DSPM pager reads
 * this field rather than being reused.
 *
 * @param totalPages 0 when {@link #total} is 0, as the vendor reports it.
 */
public record DspmPage<T>(
        List<T> data,
        int page,
        int pageSize,
        long total,
        int totalPages
) {

    public static <T> DspmPage<T> empty(int page, int pageSize) {
        return new DspmPage<>(List.of(), page, pageSize, 0L, 0);
    }
}
