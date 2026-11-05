package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * An archive search run. {@code state} is QUEUED, RUNNING, DONE, FAILED or CANCELLED. {@code rows} (at most 1000,
 * newest first) is present only when DONE; {@code errorCode} only when FAILED and is always a stable code, never an
 * SDK or server message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpArchiveSearchJob(String id, String state, XpArchiveEstimate estimate, int filesDone, int filesTotal,
                                 List<XpLogRow> rows, Boolean truncated, String errorCode, Instant createdAt,
                                 Instant finishedAt) {}
