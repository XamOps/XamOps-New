package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSavedView(Long id, @NotBlank @Size(max = 200) String name, @NotBlank String kind, boolean shared, @NotNull Map<String,Object> state) {}
