package com.xammer.cloud.xampulse.service.query;

import java.util.List;

/** Parsed log filter. Immutable; compiled to SQL only by {@link TenantScopedQueryBuilder}. */
public sealed interface FilterAst {

    record And(List<FilterAst> children) implements FilterAst {}

    record Or(List<FilterAst> children) implements FilterAst {}

    record Not(FilterAst child) implements FilterAst {}

    /** Free text on the log body (case-insensitive substring). */
    record Text(String value) implements FilterAst {}

    /**
     * Field predicate. {@code values} has several entries for {@code field:(a OR b)}. {@code cmp} is one of
     * {@code = >= > <= <}; comparisons other than {@code =} are only valid for SEVERITY.
     */
    record Field(LogField field, String attrKey, String cmp, List<String> values) implements FilterAst {}

    /** Matches everything (empty query). */
    record MatchAll() implements FilterAst {}
}
