package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A policy linked to a control, as shown in the control detail drawer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ControlPolicyRefDto {
    private String id;
    private String name;
    private String status;
}
