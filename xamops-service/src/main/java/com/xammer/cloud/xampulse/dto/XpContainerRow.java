package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One Docker container. {@code cpuPct} is percent of ONE CPU, as Docker reports it: 100 is one full core, 250 is two
 * and a half, so it can exceed 100 on a multi-core host. {@code memLimitBytes} is the container's limit (Docker
 * reports the host's memory when none is set). {@code restarts} is Docker's restart count for the container.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpContainerRow(Long accountId, String instanceId, String containerId, String name, String image, Double cpuPct,
                             Double memBytes, Double memLimitBytes, Long restarts, Instant lastSeenAt,
                             /** True when no memory limit is set (the reported limit is the host's memory); null if unknown. */
                             Boolean memLimitIsHost,
                             /** ERROR and FATAL log lines from this container in the same 15-minute window. */
                             Long errors) {}
