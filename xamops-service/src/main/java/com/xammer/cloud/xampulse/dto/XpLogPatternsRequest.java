package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogPatternsRequest(@NotNull @Valid XpScope scope, @Size(max = 2000) String query, Boolean compareToPrevious, @Min(1) @Max(100) Integer limit) {}
