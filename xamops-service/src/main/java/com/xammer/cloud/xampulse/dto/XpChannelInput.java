package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpChannelInput(Long id, @NotNull Long accountId, @NotBlank String kind, @NotBlank @Size(max = 200) String name, @NotNull XpChannelConfig config) {}
