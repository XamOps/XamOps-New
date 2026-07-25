package com.xammer.cloud.dto.compai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request body for POST /tasks. title/description required; rest optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateTaskRequestDto {
    private String title;
    private String description;
    private String assigneeId;
    private String department;
    private List<String> controlIds;
}
