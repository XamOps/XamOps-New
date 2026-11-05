package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogFacetsRequest(@NotNull @Valid XpScope scope, @Size(max = 2000) String query, @NotEmpty @Size(max = 10) List<String> fields, @Min(1) @Max(50) Integer topN) {}
