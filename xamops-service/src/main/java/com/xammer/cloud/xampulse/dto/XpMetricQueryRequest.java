package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpMetricQueryRequest(@NotNull @Valid XpScope scope, @NotEmpty @Size(max = 10) List<XpMetricSeriesSpec> series, String band, Long compareOffsetSec) {}
