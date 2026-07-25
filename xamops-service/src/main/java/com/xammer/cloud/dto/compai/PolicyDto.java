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
}
