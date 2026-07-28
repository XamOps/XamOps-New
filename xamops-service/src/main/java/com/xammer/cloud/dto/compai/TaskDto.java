package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An evidence/compliance task, aggregated from the tasks linked to each control. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDto {
    private String id;
    private String title;
    private String controlName;   // the control this task belongs to
    private String status;        // comp.ai: "todo" | "in_progress" | "done" ...
    private String assigneeId;
    private String assigneeName;  // resolved from PersonDto; null if unassigned
    private String description;   // the evidence instruction (what to collect / upload)
    private String frequency;     // review/collection cadence, e.g. "yearly" | "quarterly"
}
