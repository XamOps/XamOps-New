package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAgentFleet(Counts counts, List<XpAgentHealth> agents) {

    public record Counts(int total, int active, int stale, int pending, int revoked) {}
}
