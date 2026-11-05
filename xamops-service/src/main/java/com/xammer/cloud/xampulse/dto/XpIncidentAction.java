package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpIncidentAction(@Size(max = 2000) String comment, Instant snoozeUntil) {}
