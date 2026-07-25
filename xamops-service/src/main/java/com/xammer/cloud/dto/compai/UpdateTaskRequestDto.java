package com.xammer.cloud.dto.compai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for PATCH /tasks/:id. All fields optional — used both for the
 * quick status-only change (dropdown) and the fuller edit form. @JsonInclude(NON_NULL)
 * omits unset fields so a status-only PATCH doesn't touch title/description/assignee.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateTaskRequestDto {
    private String title;
    private String description;
    private String assigneeId;
    private String status;
}
