package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Client-to-server WebSocket frames for live tail (contract 2.5). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = XpTailClientFrame.Pause.class, name = "pause"),
        @JsonSubTypes.Type(value = XpTailClientFrame.Resume.class, name = "resume"),
        @JsonSubTypes.Type(value = XpTailClientFrame.Ping.class, name = "ping"),
        @JsonSubTypes.Type(value = XpTailClientFrame.Query.class, name = "query")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface XpTailClientFrame {
    record Pause() implements XpTailClientFrame {}
    record Resume() implements XpTailClientFrame {}
    record Ping() implements XpTailClientFrame {}
    record Query(String query) implements XpTailClientFrame {}
}
