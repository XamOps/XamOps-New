package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CloudWatch data for one instance and its EBS volumes, already turned into the units an operator reads
 * (IOPS, MiB/s, ms latency, bytes/s, credits) rather than raw per-period sums.
 * <ul>
 *   <li>{@code instance}: series key to points ({@code [epochMs, value]}): cpu.utilization, cpu.credit_balance,
 *       cpu.credit_usage, cpu.surplus_balance, cpu.surplus_charged, net.in_bps, net.out_bps, net.in_pps,
 *       net.out_pps, status.system_failed, status.instance_failed, ebs.io_balance_pct, ebs.byte_balance_pct.</li>
 *   <li>{@code volumes}: volume id to series: iops.read, iops.write, mibps.read, mibps.write, latency_ms.read,
 *       latency_ms.write, queue_length, burst_balance, idle_pct.</li>
 * </ul>
 * {@code source} is REFRESH when this response made CloudWatch calls (billed to the customer) and STORED when it
 * only read earlier fetches from XamOps (free). {@code lastFetchedAt} is null when nothing was ever fetched.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpCloudWatchView(
        String instanceId, String region, Instant from, Instant to, int periodSec,
        Map<String, List<double[]>> instance, Map<String, Map<String, List<double[]>>> volumes,
        String source, Instant lastFetchedAt, Integer metricsRequested, Double estimatedCostUsd, String note) {}
