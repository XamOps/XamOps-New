package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskDto {
    private String id;
    private String title;
    private String description;
    private String category;
    private String status;     // "open" | "mitigated" | "accepted" ...
    private String severity;   // "low" | "medium" | "high" | "critical"
    private Integer riskScore; // likelihood x impact mapped to a 1-10 scale (nullable)
    private Integer likelihood; // 1-5, for the 5x5 risk matrix (nullable)
    private Integer impact;     // 1-5, for the 5x5 risk matrix (nullable)
    private String department;
    private String owner;
    private String updatedAt;  // last modification (ISO date) for the "Updated" column
}
