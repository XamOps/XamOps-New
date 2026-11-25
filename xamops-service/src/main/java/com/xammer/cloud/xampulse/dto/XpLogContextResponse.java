package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogContextResponse(List<XpLogRow> before, XpLogRow anchor, List<XpLogRow> after) {}
