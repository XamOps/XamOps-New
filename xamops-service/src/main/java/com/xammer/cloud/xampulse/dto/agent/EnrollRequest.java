package com.xammer.cloud.xampulse.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrollRequest(String nonce, String region, StsRequest stsRequest, String agentVersion, String os, String arch, String hostname) {}
