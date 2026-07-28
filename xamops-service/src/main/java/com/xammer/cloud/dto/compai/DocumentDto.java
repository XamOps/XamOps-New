package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A required/optional evidence document tracked for the audit. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDto {
    private String id;
    private String name;
    private String description;
    private String category;   // "Governance" | "Security" | "People"
    private String status;     // "todo" | "optional" | "done" | "not_relevant"
}
