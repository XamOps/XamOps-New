package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS file drawer's Lineage tab, from the hash-lineage copies and timeline (design 5.4 item 7). The
 * Gmail lane and every event card are built by the frontend (design 4.4, 6.6).
 *
 * @param available    false when the copies route answered not found.
 * @param coverageNote {@code hash_coverage_note}: the title's tooltip on non-Gmail files.
 * @param lanes        the copies in API order, then the timeline-only lanes.
 * @param events       in API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFileLineage(boolean available, String coverageNote, List<Lane> lanes, List<Event> events) {

    /**
     * @param key         an opaque React key, also {@link Event#laneKey()}.
     * @param fileName    null on a timeline-only lane.
     * @param ownerEmail  the First Seen actor and the popover's Email.
     * @param provider    with driveType and os, the lane icon: the first non-null of the three.
     * @param channelLabel the popover's Channel.
     * @param self        the opened file's own copy (design 4.4).
     * @param firstSeenAt epoch seconds of the copy's {@code created_at}: its "First Seen" event; null on a
     *                    timeline-only lane.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Lane(String key, String fileName, String ownerName, String ownerEmail, String driveType, String os,
                       String provider, String channelLabel, boolean self, Long firstSeenAt) {
    }

    /**
     * @param laneKey    null when the event matches no lane.
     * @param occurredAt epoch seconds.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(String laneKey, String typeLabel, String actor, Long occurredAt) {
    }

    /** The copies route answered not found. */
    public static SaasFileLineage unavailable() {
        return new SaasFileLineage(false, null, List.of(), List.of());
    }
}
