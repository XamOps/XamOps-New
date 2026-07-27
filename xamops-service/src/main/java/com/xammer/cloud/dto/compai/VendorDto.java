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
}
