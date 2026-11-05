package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpKpis(int hostsReporting, int hostsTotal, double errorsPerMin, long ingestBytes24h, int openIncidents, double coveragePct) {}
