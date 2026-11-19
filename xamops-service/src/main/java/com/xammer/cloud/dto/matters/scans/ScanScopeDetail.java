package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A scope's detail (design 5.4).
 *
 * @param targets      the first page of selected datastores
 * @param targetsTotal all selected datastores
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScanScopeDetail(ScanScope scope, List<ScanTarget> targets, long targetsTotal) {
}
