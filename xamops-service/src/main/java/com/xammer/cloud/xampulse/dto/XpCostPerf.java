package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the instance costs against what it uses over the range. {@code priceBasis}: ON_DEMAND_LIST (AWS list price,
 * Linux/UNIX, shared tenancy) or SPOT_CURRENT (the latest Spot price); null when no price can be stated (another OS
 * or licence, or the price list could not be read). p95 values are of the chart's step averages. {@code verdict}:
 * UNDERSIZED, FITS, OVERSIZED, BURSTABLE (low average CPU is what a burstable class is for) or NOT_ENOUGH_DATA.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpCostPerf(String instanceId, String instanceType, Integer vcpus, Double memoryGiB, String lifecycle, String platform,
                         Double hourlyUsd, Double monthlyUsd, String priceBasis,
                         Double cpuAvgPct, Double cpuP95Pct, Double memAvgPct, Double memP95Pct,
                         Double usedVcpusAvg, Double costPerUsedVcpuHour, String verdict, String verdictText, List<String> notes) {}
