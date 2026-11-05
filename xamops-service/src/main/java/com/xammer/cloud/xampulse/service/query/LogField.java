package com.xammer.cloud.xampulse.service.query;

import java.util.Locale;
import java.util.Optional;

/** Closed set of fields the filter DSL may address. The column text never comes from user input. */
public enum LogField {
    SERVICE("service"),
    INSTANCE("instance_id"),
    SEVERITY("severity"),
    SOURCE("source"),
    REGION("region"),
    CONTAINER("container_id"),
    TRACE_ID("trace_id"),
    ATTR(null);

    private final String column;

    LogField(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }

    /** Resolves a DSL field name; {@code attr.<key>} is handled by the parser. */
    public static Optional<LogField> fromName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "service" -> Optional.of(SERVICE);
            case "instance", "instance_id", "host" -> Optional.of(INSTANCE);
            case "severity", "level" -> Optional.of(SEVERITY);
            case "source" -> Optional.of(SOURCE);
            case "region" -> Optional.of(REGION);
            case "container" -> Optional.of(CONTAINER);
            case "trace_id" -> Optional.of(TRACE_ID);
            default -> Optional.empty();
        };
    }
}
