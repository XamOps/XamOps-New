package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A framework requirement mapped to a control, as shown in the control detail drawer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ControlRequirementRefDto {
    private String identifier;
    private String name;
}
