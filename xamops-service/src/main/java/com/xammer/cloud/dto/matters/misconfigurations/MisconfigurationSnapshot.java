package com.xammer.cloud.dto.matters.misconfigurations;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Cache only: every policy of a project in row order, from which both Misconfigurations endpoints are
 * answered. Never serialized to the browser as is.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MisconfigurationSnapshot(int datastoresRead, boolean capped, List<MisconfigurationDetail> policies) {
}
