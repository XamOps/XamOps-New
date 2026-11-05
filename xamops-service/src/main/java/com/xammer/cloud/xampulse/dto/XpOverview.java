package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpOverview(boolean demo, XpAccountState state, Instant lastDataAt, Integer healthScore, XpKpis kpis, List<String> narrative, List<XpAttentionItem> attention, List<XpHeatTile> tiles) {}
