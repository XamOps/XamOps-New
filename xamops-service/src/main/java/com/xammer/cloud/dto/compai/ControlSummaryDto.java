package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A SOC 2 control as surfaced in the XamOps SOC2 Program UI. comp.ai controls
 * carry no direct status or owner, so {@code status} is derived from linked
 * policy/task state and {@code category} from the mapped requirement identifier
 * (e.g. "CC6.1" -> "CC6"). See CompAiService.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ControlSummaryDto {
    private String id;
    private String name;
    private String category;          // requirement prefix, e.g. "CC6", "A1", "C1"
    private String requirementName;   // full requirement text (nullable)
    private String status;            // "PASSING" | "IN_PROGRESS" | "NOT_STARTED"
    private String policyName;        // first linked policy (nullable)
    private String policyStatus;      // first linked policy status (nullable)
    private int taskCount;
}
