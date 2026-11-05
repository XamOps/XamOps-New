package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpArchiveEstimate(long bytes, long requests, double usd, List<String> storageClasses, String state) {}
