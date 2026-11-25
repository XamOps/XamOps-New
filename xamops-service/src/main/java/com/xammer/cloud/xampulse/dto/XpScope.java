package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpScope(@NotEmpty @Size(max = 20) List<Long> accountIds, List<String> regions, @NotNull Instant from, @NotNull Instant to, XpResourceFilter resource) {}
