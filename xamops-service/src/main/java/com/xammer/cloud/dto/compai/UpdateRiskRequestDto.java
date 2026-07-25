package com.xammer.cloud.dto.compai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for PATCH /risks/:id. All fields optional. {@code @JsonInclude(NON_NULL)}
 * omits any unset field from the serialized JSON entirely (rather than sending literal
 * {@code null}) so a partial edit can't accidentally clear fields the form didn't touch.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateRiskRequestDto {
    private String title;
    private String description;
    private String category;
    private String department;
}
