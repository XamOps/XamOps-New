package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAlertRule(Long id, Long version, @NotBlank @Size(max = 200) String name, boolean enabled, @NotBlank String severity, @NotNull @Valid XpAlertScope scope, @NotNull @Valid XpCondition condition, XpGrouping grouping, XpRouting routing) {}
