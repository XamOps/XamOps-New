package com.xammer.cloud.service.matters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared helpers of build 6's query records: optional vendor params and the canonical cache fragment. */
final class QueryFragments {

    private QueryFragments() {
    }

    /** Puts {@code name=value} when the value is not null. */
    static void putIfSet(Map<String, List<String>> params, String name, Object value) {
        if (value != null) {
            params.put(name, List.of(String.valueOf(value)));
        }
    }

    /** {@code name=value&…} in map order, names and values URL-encoded; absent params are not in the map. */
    static String canonical(Map<String, List<String>> params) {
        return params.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(v -> encode(e.getKey()) + "=" + encode(v)))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
