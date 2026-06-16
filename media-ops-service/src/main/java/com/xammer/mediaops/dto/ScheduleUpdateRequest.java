package com.xammer.mediaops.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class ScheduleUpdateRequest {
    private Instant scheduledAt;
}
