package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHealthScore(Integer score, List<XpLane> lanes, Instant asOf, List<String> explanation) {}
