package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** Server-to-client WebSocket frames for live tail (contract 2.5). Discriminator property is {@code t}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = XpTailServerFrame.Rows.class, name = "rows"),
        @JsonSubTypes.Type(value = XpTailServerFrame.Stats.class, name = "stats"),
        @JsonSubTypes.Type(value = XpTailServerFrame.Notice.class, name = "notice"),
        @JsonSubTypes.Type(value = XpTailServerFrame.End.class, name = "end")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface XpTailServerFrame {

    record Rows(List<XpLogRow> rows, String cursor, double rate) implements XpTailServerFrame {}

    record Stats(long matched, long dropped) implements XpTailServerFrame {}

    /** code: RATE_CAPPED | QUERY_REVALIDATED | IDLE_WARNING */
    record Notice(String code, String message) implements XpTailServerFrame {}

    /** reason: IDLE_TIMEOUT | MAX_DURATION | REVOKED | ERROR | CLIENT_CLOSED */
    record End(String reason, String message) implements XpTailServerFrame {}
}
