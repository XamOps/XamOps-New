package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Something that changed on a host, and what the measurements did right after it. Effects say what followed the
 * change, never that the change caused it: {@code overlapping} marks a change with another one within 10 minutes on
 * the same host, where the two cannot be told apart.
 *
 * @param kind    DEPLOY (new image), RECREATE (same image, new container), CONTAINER_START, CONTAINER_STOP, RESTART
 *                (container restart), REBOOT, DATA_GAP, PACKAGE, CONFIG, EC2_STATE, AGENT
 * @param source  where it was seen: AGENT (metrics), LOGS (package manager log), PLATFORM (recorded event)
 * @param subject the container name for container changes, else null
 * @param verdict FOLLOWED_BY_CHANGE, NO_CLEAR_EFFECT, TOO_RECENT, NOT_ENOUGH_DATA, or null when effects were not
 *                computed (fleet-wide lists)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpChange(Instant ts, String instanceId, String kind, String title, String detail, String source, String subject,
                       String verdict, boolean overlapping, List<Effect> effects) {

    /**
     * @param before   median over the 30 minutes before
     * @param after    median over the 30 minutes after (the change minute itself is left out)
     * @param strength STRONG or MODERATE (weaker shifts are not reported)
     */
    public record Effect(String signal, String label, String unit, double before, double after, double relative, String strength) {}
}
