package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpBucketCandidate(String name, String region, String encryption, Boolean versioning, Boolean publicAccessBlocked,
        Boolean lifecycleHasXamPulseRule, boolean recommended, List<XpBucketIssue> issues) {}
