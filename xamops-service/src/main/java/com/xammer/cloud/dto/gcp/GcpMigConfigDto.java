package com.xammer.cloud.dto.gcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Represents the editable configuration of a GCP Managed Instance Group (MIG)
 * for AutoSpotting purposes. Mirrors AWS ASGConfig in spirit.
 */
@Data
public class GcpMigConfigDto {

    @JsonProperty("migName")
    private String migName;

    @JsonProperty("region")
    private String region;

    @JsonProperty("isSpotEnabled")
    private boolean isSpotEnabled;

    /** The MIG's current target size */
    @JsonProperty("targetSize")
    private int targetSize;

    // ── Rolling Update Policy ─────────────────────────────────────────────────

    /**
     * Number of extra VMs to create above targetSize during a rolling update.
     * Default 1 means one new (Spot) VM is created before an old one is removed.
     */
    @JsonProperty("maxSurge")
    private int maxSurge = 1;

    /**
     * Maximum VMs that can be UNAVAILABLE during a rolling update.
     * Keep at 0 for zero-downtime (no existing VM is terminated until a new one is RUNNING).
     */
    @JsonProperty("maxUnavailable")
    private int maxUnavailable = 0;

    /**
     * Seconds a new VM must be healthy/running before the updater considers it ready
     * and proceeds to remove the old VM.
     */
    @JsonProperty("minReadySeconds")
    private int minReadySeconds = 30;

    // ── Autoscaler ────────────────────────────────────────────────────────────

    /** Whether an Autoscaler is attached to this MIG */
    @JsonProperty("autoscalerEnabled")
    private boolean autoscalerEnabled;

    @JsonProperty("autoscalerMinReplicas")
    private Integer autoscalerMinReplicas;

    @JsonProperty("autoscalerMaxReplicas")
    private Integer autoscalerMaxReplicas;

    @JsonProperty("autoscalerCoolDownPeriodSec")
    private Integer autoscalerCoolDownPeriodSec;

    // ── Machine Type ──────────────────────────────────────────────────────────

    @JsonProperty("machineType")
    private String machineType;

    /** Human-readable description of what the Spot conversion will do */
    @JsonProperty("updatePolicySummary")
    private String updatePolicySummary;
}
