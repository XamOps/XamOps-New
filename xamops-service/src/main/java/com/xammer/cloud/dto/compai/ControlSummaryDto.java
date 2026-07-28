package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A SOC 2 control as surfaced in the XamOps SOC2 Program UI. comp.ai controls
 * carry no direct status or owner, so {@code status} is derived from linked
 * policy/task state and {@code category} from the mapped requirement identifier
 * (e.g. "CC6.1" -> "CC6"). See CompAiService.
 *
 * <p>{@code policyName}/{@code policyStatus}/{@code requirementName} are the
 * first linked item (kept for the table row); {@code policies}/{@code requirements}/
 * {@code tasks} carry the FULL lists, used by the detail drawer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ControlSummaryDto {
    private String id;
    private String name;
    private String description;
    private String category;          // requirement prefix, e.g. "CC6", "A1", "C1"
    private String requirementName;   // full requirement text of the FIRST mapped requirement (nullable)
    private String status;            // "PASSING" | "IN_PROGRESS" | "NOT_STARTED"
    private String policyName;        // first linked policy (nullable)
    private String policyStatus;      // first linked policy status (nullable)
    private int taskCount;

    // Progress counters for the framework control table (published/total, done/total).
    private int compliancePercent;    // (publishedPolicies + doneTasks) / (totalPolicies + totalTasks)
    private int policiesPublished;
    private int policiesTotal;
    private int tasksDone;
    private int tasksTotal;
    private int documentsCompleted;   // no per-control documents over the API -> 0/0
    private int documentsTotal;

    private List<ControlRequirementRefDto> requirements;
    private List<ControlPolicyRefDto> policies;
    private List<TaskDto> tasks;
}
