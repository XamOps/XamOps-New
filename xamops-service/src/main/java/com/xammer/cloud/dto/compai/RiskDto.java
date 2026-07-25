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
    private String owner;
}
