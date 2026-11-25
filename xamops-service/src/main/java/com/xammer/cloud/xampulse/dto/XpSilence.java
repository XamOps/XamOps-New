package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSilence(Long id, @NotNull Long accountId, @NotNull @Valid XpSilenceMatcher matcher, @NotNull Instant startsAt, @NotNull Instant endsAt, @Size(max = 500) String reason) {}
