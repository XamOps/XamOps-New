package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated Files page SaaS query (F1; design 5.3 as changed by plan C3 to C5).
 *
 * @param limit         page size, 1..100
 * @param cursor        the vendor's keyset cursor from the previous page, or null for the first page
 * @param search        the trimmed file-name search, or null
 * @param sensitives    sensitive-category codes, sent as {@code label}
 * @param documentClass one document class, sent as a plain {@code subcategory} string, or null
 * @param sharing       {@code internal}, {@code external} or {@code public}, or null for any access
 * @param categories    document categories
 * @param appTypes      app-type codes such as {@code GMAIL}, sent as {@code source}
 * @param owners        24-hex owner ids from the owners list, lower-cased, sent as {@code owners}
 * @param internalUsers internal user e-mails
 * @param externalUsers external user e-mails
 */
public record SaasFileListQuery(int limit, String cursor, String search, List<String> sensitives,
                                String documentClass, String sharing, List<String> categories,
                                List<String> appTypes, List<String> owners, List<String> internalUsers,
                                List<String> externalUsers) {

    /** The F1 parameter names. */
    public static final Set<String> PARAMETERS = Set.of("limit", "cursor", "q", "sensitives", "documentClass",
            "sharing", "category", "appType", "owner", "internalUser", "externalUser");

    /** The Sharing Mode choices, in the filter's order. */
    public static final List<String> SHARING = List.of("internal", "external", "public");

    /**
     * Reads the query. Control parameters ({@code limit}, {@code cursor}, {@code q}, {@code documentClass},
     * {@code sharing}) may be given once; the list filters follow {@link FilterValues}. Other names are left to
     * the controller's {@code requireAllowed}.
     *
     * @throws DspmBadRequestException naming the parameter, never the value
     */
    public static SaasFileListQuery parse(MultiValueMap<String, String> params) {
        return new SaasFileListQuery(
                DatastoreParams.limit(DatastoreParams.single(params, "limit")),
                DatastoreParams.cursor(DatastoreParams.single(params, "cursor")),
                DatastoreParams.search(DatastoreParams.single(params, "q")),
                FilterValues.parse(params, "sensitives", FilterValues.Rule.CODE),
                documentClass(DatastoreParams.single(params, "documentClass")),
                DatastoreParams.choice("sharing", DatastoreParams.single(params, "sharing"), SHARING),
                FilterValues.parse(params, "category", FilterValues.Rule.TEXT),
                FilterValues.parse(params, "appType", FilterValues.Rule.CODE),
                FilterValues.parse(params, "owner", FilterValues.Rule.HEX24),
                FilterValues.parse(params, "internalUser", FilterValues.Rule.EMAIL),
                FilterValues.parse(params, "externalUser", FilterValues.Rule.EMAIL));
    }

    /** F2's optional {@code category} list, by the same rule as F1's (TEXT). */
    public static List<String> categoryFilter(MultiValueMap<String, String> params) {
        return FilterValues.parse(params, "category", FilterValues.Rule.TEXT);
    }

    /** One document class: trimmed, blank is absent, then the TEXT rule. Commas are part of the value. */
    private static String documentClass(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return FilterValues.check("documentClass", raw.trim(), FilterValues.Rule.TEXT);
    }

    /**
     * The vendor params in this order: {@code limit}; {@code cursor} and {@code name_search_str} when set;
     * {@code label}; {@code subcategory} as a plain string; {@code category}; {@code source}; {@code owners};
     * {@code internal_users}; {@code external_users}; then the one share flag. Every list is one JSON array
     * written by Jackson, and only when non-empty.
     */
    public Map<String, List<String>> toVendorParams(ObjectMapper objectMapper) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("limit", List.of(String.valueOf(limit)));
        if (cursor != null) {
            params.put("cursor", List.of(cursor));
        }
        if (search != null) {
            params.put("name_search_str", List.of(search));
        }
        EndpointFilters.putArray(params, "label", sensitives, objectMapper);
        if (documentClass != null) {
            params.put("subcategory", List.of(documentClass));
        }
        EndpointFilters.putArray(params, "category", categories, objectMapper);
        EndpointFilters.putArray(params, "source", appTypes, objectMapper);
        EndpointFilters.putArray(params, "owners", owners, objectMapper);
        EndpointFilters.putArray(params, "internal_users", internalUsers, objectMapper);
        EndpointFilters.putArray(params, "external_users", externalUsers, objectMapper);
        if (sharing != null) {
            params.put(sharing + "_share", List.of("true"));
        }
        return params;
    }

    /**
     * {@code limit=..&cursor=..&q=..&sensitives=..&documentClass=..&sharing=..&category=..&appType=..&owner=..
     * &internalUser=..&externalUser=..}, values URL-encoded and lists comma-joined. The lists are sorted, so the
     * fragment depends on neither value nor parameter order. Hashed into the cache key, never logged.
     */
    public String canonicalFragment() {
        return "limit=" + limit
                + "&cursor=" + encode(cursor)
                + "&q=" + encode(search)
                + "&sensitives=" + EndpointFilters.join(sensitives)
                + "&documentClass=" + encode(documentClass)
                + "&sharing=" + encode(sharing)
                + "&category=" + EndpointFilters.join(categories)
                + "&appType=" + EndpointFilters.join(appTypes)
                + "&owner=" + EndpointFilters.join(owners)
                + "&internalUser=" + EndpointFilters.join(internalUsers)
                + "&externalUser=" + EndpointFilters.join(externalUsers);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
