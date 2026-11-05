package com.xammer.cloud.xampulse.service.alert;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Groups new firings into an existing open incident when they share an instance or arrive within 10 minutes. */
@Service("xpIncidentCorrelator")
public class IncidentCorrelator {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    public record OpenIncident(long id, List<String> instanceIds, Instant lastActivityAt) {}

    public Optional<OpenIncident> pick(List<OpenIncident> open, String instanceId, Instant now) {
        for (OpenIncident i : open) {
            if (instanceId != null && i.instanceIds().contains(instanceId)) {
                return Optional.of(i);
            }
        }
        for (OpenIncident i : open) {
            if (Duration.between(i.lastActivityAt(), now).compareTo(WINDOW) <= 0) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
