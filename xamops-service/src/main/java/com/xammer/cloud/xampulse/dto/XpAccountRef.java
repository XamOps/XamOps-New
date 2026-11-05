package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAccountRef(Long cloudAccountId, String awsAccountId, String name, XpAccountState state, boolean demo) {}
