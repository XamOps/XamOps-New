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
}
