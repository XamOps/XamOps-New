package com.xammer.cloud.service.matters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated SaaS file-list query (design 5.3): the files of one data element or one document class
 * of a SaaS datastore, a page at a time.
 *
 * <p>Built from values already checked by {@link DatastoreParams}; the membership of {@code value} in
 * the datastore's records is checked by the service.
 *
 * @param kind   which records view the value belongs to
 * @param value  the data element or document class name, as listed in the records
 * @param search the trimmed search term, or null when absent
 * @param skip   row offset, 0..10,000
 * @param limit  page size, 1..100
 */
public record SaasFileQuery(Kind kind, String value, String search, int skip, int limit) {

    /** The records view a value comes from. */
    public enum Kind {
        DATA_ELEMENT,
        DOCUMENT_CLASS
    }

    /**
     * {@code …/saas/asset/{id}/files}: {@code limit}, {@code skip}, {@code search} when set, then
     * {@code sensitive_entity} or {@code subcategory}, both plain strings (GAP-20).
     */
    public Map<String, List<String>> toVendorParams() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("limit", List.of(String.valueOf(limit)));
        params.put("skip", List.of(String.valueOf(skip)));
        if (search != null) {
            params.put("search", List.of(search));
        }
        params.put(kind == Kind.DATA_ELEMENT ? "sensitive_entity" : "subcategory", List.of(value));
        return params;
    }

    /** {@code kind=..&value=..&q=..&skip=..&limit=..}. Hashed into the cache key, never logged. */
    public String canonicalFragment() {
        return "kind=" + kind.name()
                + "&value=" + encode(value)
                + "&q=" + encode(search == null ? "" : search)
                + "&skip=" + skip
                + "&limit=" + limit;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
