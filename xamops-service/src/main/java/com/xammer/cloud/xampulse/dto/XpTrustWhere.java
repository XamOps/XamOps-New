package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpTrustWhere(String hotRegion, int hotDays, String archiveBucket, String archiveRegion, String kms) {}
