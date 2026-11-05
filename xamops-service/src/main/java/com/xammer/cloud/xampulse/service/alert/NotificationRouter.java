package com.xammer.cloud.xampulse.service.alert;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.xampulse.service.alert.channel.ChannelConfig;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Decides whether and where a notification goes. Silences suppress delivery (the incident still exists), repeats of
 * the same firing inside the dedupe window are dropped, and demo-data alerts never reach external channels.
 */
@Service("xpNotificationRouter")
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    public enum Status { SENT, SUPPRESSED_SILENCED, SUPPRESSED_DEDUPE, SKIPPED_DEMO, NO_CHANNELS, FAILED }

    public record Result(Status status, int delivered, int failed) {}

    private final ChannelService channels;
    private final Cache<String, Instant> lastSent = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24)).maximumSize(100_000).build();

    public NotificationRouter(ChannelService channels) {
        this.channels = channels;
    }

    public Result route(Long accountId, Long ruleId, String groupKey, Duration dedupe, List<Long> channelIds,
                        Notification n, boolean silenced, Instant now) {
        if (silenced) {
            return new Result(Status.SUPPRESSED_SILENCED, 0, 0);
        }
        if (n.instanceId() != null && n.instanceId().startsWith("i-demo")) {
            return new Result(Status.SKIPPED_DEMO, 0, 0);
        }
        if (channelIds == null || channelIds.isEmpty()) {
            return new Result(Status.NO_CHANNELS, 0, 0);
        }
        if ("FIRED".equals(n.kind())) {
            String key = ruleId + "|" + groupKey + "|FIRED";
            Instant prev = lastSent.getIfPresent(key);
            if (prev != null && Duration.between(prev, now).compareTo(dedupe) < 0) {
                return new Result(Status.SUPPRESSED_DEDUPE, 0, 0);
            }
            lastSent.put(key, now);
        }
        int ok = 0;
        int bad = 0;
        for (Long id : channelIds) {
            try {
                String kind = channels.kindOf(id, accountId);
                ChannelConfig cfg = channels.load(id, accountId);
                if (kind == null || cfg == null) {
                    bad++;
                    continue;
                }
                channels.sender(kind).send(cfg, n);
                ok++;
            } catch (Exception e) {
                bad++;
                // No destination or payload in the log line.
                log.warn("XamPulse notification failed rule={} channel={} reason={}", ruleId, id, e.getClass().getSimpleName());
            }
        }
        return new Result(ok > 0 ? Status.SENT : Status.FAILED, ok, bad);
    }
}
