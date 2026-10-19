package com.xammer.cloud.service.automation.workload.api.dto;

import java.util.List;

/**
 * One page of the activity feed ({@code GET /api/eks/automation/activity/{cluster}}): the recorded
 * entries, newest first, and the cursor of the next (older) page.
 *
 * @param engine     whether the Workload autoscaler is switched on in this environment. The history
 *                   is served either way — it is a record of the past — and the UI says when the
 *                   engine is off
 * @param entries    newest first
 * @param nextCursor opaque; pass it back as {@code cursor} for the next page. Null when there is none
 */
public record ActivityPageDto(Engine engine, List<ActivityEntryDto> entries, String nextCursor) {

    public ActivityPageDto {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Engine(boolean enabled) {
    }
}
