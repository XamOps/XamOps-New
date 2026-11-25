package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogSearchResponse(List<XpLogRow> rows, String nextCursor, XpScanned scanned, long tookMs, boolean truncated, XpParsed parsed) {}
