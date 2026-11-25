package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One process row for the Resource 360 "Top processes" table. Mirrors the TypeScript {@code XpProcessRow}
 * (frontend-app/src/types/xampulse.ts). Only available when the host's collection level is T2; otherwise the
 * list is empty. {@code user}, {@code memBytes} and {@code threads} are optional and omitted when unknown
 * (the record is NON_NULL).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpProcessRow(long pid, String name, String user, double cpuPct, double memPct,
                           Long memBytes, Integer threads) {}
