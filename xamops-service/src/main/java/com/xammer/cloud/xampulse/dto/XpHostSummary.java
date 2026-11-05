package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHostSummary(XpHostRow host, List<XpLane> lanes, Map<String,List<double[]>> series, List<XpLogRow> recentErrors, List<XpEvent> events, List<XpIncident> openIncidents) {}
