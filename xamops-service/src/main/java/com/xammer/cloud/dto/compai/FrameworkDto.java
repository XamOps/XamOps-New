package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A compliance framework the org is working against (e.g. SOC 2 Type I & II). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FrameworkDto {
    private String id;                // framework instance id
    private String name;              // e.g. "SOC 2"
    private String description;       // e.g. "SOC 2 Type I & II"
    private int controlsTotal;
    private int controlsCompleted;    // controls at 100% compliance
    private int compliancePercent;    // average of per-control compliance
}
