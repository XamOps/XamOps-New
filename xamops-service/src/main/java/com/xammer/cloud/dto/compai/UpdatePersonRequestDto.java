package com.xammer.cloud.dto.compai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for PATCH /people/:id. All fields optional; unset fields are omitted. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdatePersonRequestDto {
    private String role;
    private String department;
    private Boolean isActive;
}
