package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpChecklistCheck(String id, String region, String label, String state, String hint, String docUrl) {}
