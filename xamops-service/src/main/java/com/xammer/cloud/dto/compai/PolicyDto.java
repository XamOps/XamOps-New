package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyDto {
    private String id;
    private String name;
    private String description;
    private String status;   // comp.ai: "draft" | "published" | "needs_review" ...
    private String content;  // plain text extracted from comp.ai's tiptap/prosemirror rich-text JSON
    private String frequency;         // review cadence, e.g. "yearly" | "quarterly"
    private String department;        // owning department, e.g. "it" | "hr" | "gov"
    private boolean requiresSignature; // whether employees must acknowledge/sign it
    private String reviewDate;         // next scheduled review (ISO date), if set
    private String lastPublishedAt;    // when the current version was published, if ever
}
