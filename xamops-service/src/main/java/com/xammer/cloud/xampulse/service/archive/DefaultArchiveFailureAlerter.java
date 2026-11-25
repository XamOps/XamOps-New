package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpChannel;
import com.xammer.cloud.xampulse.repository.XpChannelRepository;
import com.xammer.cloud.xampulse.service.alert.NotificationRouter;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import com.xammer.cloud.xampulse.service.insight.EventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Writes an {@code ALERT} timeline event and notifies the account's channels. Both paths catch their own failures and
 * log only the exception class, so neither can mask the archive failure or change the DEGRADED transition.
 */
@Component("xpArchiveFailureAlerter")
@ConditionalOnProperty(name = {"xampulse.enabled", "xampulse.archive.enabled"}, havingValue = "true")
public class DefaultArchiveFailureAlerter implements ArchiveFailureAlerter {

    private static final Logger log = LoggerFactory.getLogger(DefaultArchiveFailureAlerter.class);
    private static final Duration DEDUPE = Duration.ofHours(24);

    private final EventWriter events;
    private final NotificationRouter router;
    private final XpChannelRepository channels;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultArchiveFailureAlerter(EventWriter events, NotificationRouter router, XpChannelRepository channels) {
        this(events, router, channels, Clock.systemUTC());
    }

    DefaultArchiveFailureAlerter(EventWriter events, NotificationRouter router, XpChannelRepository channels, Clock clock) {
        this.events = events;
        this.router = router;
        this.channels = channels;
        this.clock = clock;
    }

    @Override
    public void archiveFailing(XpAccountConfig cfg, int failures, String errorClass) {
        Long account = cfg.getCloudAccountId();
        if (account == null) {
            return;
        }
        Instant now = clock.instant();
        String detail = failures + " runs in a row failed";
        try {
            events.record(account, "ALERT", "archive", "Archive writer failing", detail, now);
        } catch (Exception e) {
            log.warn("XamPulse archive alert event failed for account {}: {}", account, e.getClass().getSimpleName());
        }
        try {
            List<Long> ids = channels.findByCloudAccountId(account).stream().map(XpChannel::getId).toList();
            router.route(account, 0L, "archive", DEDUPE, ids,
                    new Notification("FIRED", "XamPulse archive writer failing",
                            detail + ". Check the archive bucket and role in Storage.", "HIGH", account, null, null, null, now),
                    false, now);
        } catch (Exception e) {
            log.warn("XamPulse archive alert notification failed for account {}: {}", account, e.getClass().getSimpleName());
        }
    }
}
