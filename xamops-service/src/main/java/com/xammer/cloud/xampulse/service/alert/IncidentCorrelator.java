package com.xammer.cloud.xampulse.service.alert;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a new firing joins an open incident.
 *
 * <ol>
 *   <li>Same instance: the firing joins that instance's open incident, however old.</li>
 *   <li>Same rule within {@link #WINDOW} of the incident's last activity: one rule firing across many hosts (a bad
 *       deploy, a shared dependency) is one incident, not twenty.</li>
 *   <li>Same tier within {@link #WINDOW}: hosts of one Auto Scaling group (or of one instance type and name pattern,
 *       web-1, web-2...) going wrong together, on different rules (CPU on one, errors on another), are one incident.</li>
 *   <li>Otherwise a new incident. Different rules on unrelated hosts used to merge just because they fired within
 *       10 minutes of each other, which hid the second, unrelated problem inside the first incident.</li>
 * </ol>
 */
@Service("xpIncidentCorrelator")
public class IncidentCorrelator {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    public record OpenIncident(long id, List<String> instanceIds, List<Long> ruleIds, Instant lastActivityAt) {}

    /** The incident joined and why, in words for its timeline. */
    public record Join(OpenIncident incident, String reason) {}

    public Optional<OpenIncident> pick(List<OpenIncident> open, String instanceId, Long ruleId, Instant now) {
        return join(open, instanceId, ruleId, now, ids -> Map.of()).map(Join::incident);
    }

    /**
     * @param tiers instance id to tier key (ASG, else type and name pattern) and a readable label, for the given ids;
     *              asked only when rules 1 and 2 found nothing and a recent incident exists, and may return nothing
     */
    public Optional<Join> join(List<OpenIncident> open, String instanceId, Long ruleId, Instant now,
                               Function<Collection<String>, Map<String, Tier>> tiers) {
        for (OpenIncident i : open) {
            if (instanceId != null && i.instanceIds().contains(instanceId)) {
                return Optional.of(new Join(i, null));
            }
        }
        for (OpenIncident i : open) {
            if (ruleId != null && i.ruleIds().contains(ruleId) && recent(i, now)) {
                return Optional.of(new Join(i, null));
            }
        }
        List<OpenIncident> recent = open.stream().filter(i -> recent(i, now)).toList();
        if (instanceId == null || recent.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(instanceId);
        recent.forEach(i -> ids.addAll(i.instanceIds()));
        Map<String, Tier> t = tiers.apply(ids);
        Tier mine = t == null ? null : t.get(instanceId);
        if (mine == null) {
            return Optional.empty();
        }
        for (OpenIncident i : recent) {
            for (String other : i.instanceIds()) {
                Tier theirs = t.get(other);
                if (theirs != null && theirs.key().equals(mine.key())) {
                    return Optional.of(new Join(i, "same tier as " + other + " (" + mine.label() + ")"));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean recent(OpenIncident i, Instant now) {
        return Duration.between(i.lastActivityAt(), now).compareTo(WINDOW) <= 0;
    }

    /** A host's tier: {@code key} compares, {@code label} is shown ("Auto Scaling group web", "m5.large named web-*"). */
    public record Tier(String key, String label) {}
}
