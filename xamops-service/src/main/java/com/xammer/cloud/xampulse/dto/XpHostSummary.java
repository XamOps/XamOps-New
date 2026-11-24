package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One host for Host 360.
 *
 * @param bands            normal range per series key, as {@code [t, low, high]} in the series' own unit (percent for
 *                         CPU, memory and disk, raw for load); only keys with a learned baseline, none on cold start
 * @param compare          the main series over the same window {@code compareOffsetSec} earlier, with timestamps moved
 *                         forward by the offset so they line up with today's points; null when not asked for
 * @param compareOffsetSec the offset that was compared, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHostSummary(XpHostRow host, List<XpLane> lanes, Map<String, List<double[]>> series, List<XpLogRow> recentErrors,
                            List<XpEvent> events, List<XpIncident> openIncidents, Map<String, List<double[]>> bands,
                            Map<String, List<double[]>> compare, Long compareOffsetSec) {

    public XpHostSummary(XpHostRow host, List<XpLane> lanes, Map<String, List<double[]>> series, List<XpLogRow> recentErrors,
                         List<XpEvent> events, List<XpIncident> openIncidents) {
        this(host, lanes, series, recentErrors, events, openIncidents, null, null, null);
    }
}
