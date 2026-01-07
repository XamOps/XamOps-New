package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyInstance {
    private String instanceType;
    private String platformCode;
    private String tenancyCode;
    private Integer vcpu;
    private Integer memory;
    private Integer qty;
}
