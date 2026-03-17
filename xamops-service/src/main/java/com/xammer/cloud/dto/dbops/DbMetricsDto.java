package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbMetricsDto {
    private String instanceId;
    private String region;
    // ── Utilisation (already existing) ──────────────────────────────────────
    private double avgCpuPercent;
    private double avgConnections;
    private double avgFreeableMemoryMb;
    private double avgReadIops;
    private double avgWriteIops;
    // ── Latency (ReadLatency / WriteLatency → ms) ────────────────────────────
    private double avgReadLatencyMs;
    private double avgWriteLatencyMs;
    // ── I/O pressure ────────────────────────────────────────────────────────
    private double avgDiskQueueDepth;
    // ── Storage (FreeStorageSpace → GB) ─────────────────────────────────────
    private double freeStorageSpaceGb;
    // ── Replication (ReplicaLag → seconds) ───────────────────────────────────
    private double replicaLagSeconds;
    // ── Network (bytes/sec → MB/s) ────────────────────────────────────────────
    private double avgNetworkReceiveMbps;
    private double avgNetworkTransmitMbps;
    // ── Memory pressure ──────────────────────────────────────────────────────
    private double avgSwapUsageMb;
    // ── Storage burst (gp2 BurstBalance %) ───────────────────────────────────
    private double burstBalance;
}
