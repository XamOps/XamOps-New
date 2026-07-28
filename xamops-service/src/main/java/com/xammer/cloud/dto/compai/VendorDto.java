package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorDto {
    private String id;
    private String name;
    private String category;
    private String status;
    private String description;
    private String website;
    private boolean subProcessor;
    private String inherentSeverity;  // derived: "low" | "medium" | "high" | "critical" | null
    private String residualSeverity; // derived, after applying the vendor's controls/mitigations
    private Integer inherentScore;    // inherent probability x impact on a 1-10 scale (nullable)
    private Integer residualScore;    // residual probability x impact on a 1-10 scale (nullable)
    // Raw 1-5 axes for the 5x5 risk matrix (nullable when comp.ai enums are unset).
    private Integer inherentLikelihood;
    private Integer inherentImpact;
    private Integer residualLikelihood;
    private Integer residualImpact;
}
