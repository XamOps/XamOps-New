package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** {@code confirmedBytes} is the estimate the user agreed to read; the server refuses if the new estimate is larger. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpArchiveSearchRunRequest(@NotNull @Valid XpScope scope, @Size(max = 2000) String query,
                                        @PositiveOrZero long confirmedBytes) {}
