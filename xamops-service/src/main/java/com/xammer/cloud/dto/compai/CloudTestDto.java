package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A cloud-security check result ("Cloud Tests" in comp.ai). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudTestDto {
    private String id;
    private String title;
    private String severity;   // "low" | "medium" | "high" | "critical"
    private String status;     // "open" | "resolved" | "exception" ...
    private String provider;   // "aws" | "gcp" | "azure"
    private String resource;
}
