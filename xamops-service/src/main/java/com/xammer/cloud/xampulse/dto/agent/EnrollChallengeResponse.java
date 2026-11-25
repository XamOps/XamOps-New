package com.xammer.cloud.xampulse.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrollChallengeResponse(String nonce, int expiresInSec) {}
