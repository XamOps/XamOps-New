package com.xammer.cloud.service.autospotting.aws.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AWS event pipeline's settings (design-aws.md §9.5, §13.2, plan-phase-22.md task 22.2-22.3).
 *
 * <ul>
 *   <li>{@code autospotting.aws.events.enabled} (code default {@code false}): the listener and the queue
 *       policy sync run only when true.</li>
 *   <li>{@code autospotting.aws.events.queue-url}: set only in the UAT and prod profiles, never in the
 *       base or local profile. UAT names the {@code -uat} queue, prod the unsuffixed one.</li>
 *   <li>{@code autospotting.aws.events.record-only} (code default {@code true}): events are recorded as
 *       analytics rows and trigger no action, not even a reconcile hint. Setting it false is the Phase 23
 *       decision; no profile does it here.</li>
 * </ul>
 *
 * <p>{@link #refusal()} is the environment guard (FR-SPN-10, AC-SPN-11): a profile other than
 * {@code prod} that points at an unsuffixed (production) queue is refused, so a laptop or UAT node can
 * never consume prod events. Stricter than the inventory listener, which only warns.
 */
@Component
public class AwsEventProperties {

    private static final Logger log = LoggerFactory.getLogger(AwsEventProperties.class);

    /** {@code xamops-autospotting-events{suffix}.fifo}; the suffix is empty for prod and {@code -uat} for UAT. */
    static final Pattern QUEUE_NAME = Pattern.compile("^xamops-autospotting-events(-[a-z0-9]+)?\\.fifo$");
    /** The legacy queue is a production queue too: no non-prod profile may consume it through this listener. */
    static final String LEGACY_QUEUE_NAME = "autospotting-events.fifo";

    private final boolean enabled;
    private final String queueUrl;
    private final boolean recordOnly;
    private final boolean prodProfile;

    @Autowired
    public AwsEventProperties(Environment environment,
                              @Value("${autospotting.aws.events.enabled:false}") boolean enabled,
                              @Value("${autospotting.aws.events.queue-url:}") String queueUrl,
                              @Value("${autospotting.aws.events.record-only:true}") boolean recordOnly) {
        this(environment.acceptsProfiles(Profiles.of("prod")), enabled, queueUrl, recordOnly);
    }

    /** For tests and callers that already know the profile. */
    public AwsEventProperties(boolean prodProfile, boolean enabled, String queueUrl, boolean recordOnly) {
        this.prodProfile = prodProfile;
        this.enabled = enabled;
        this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
        this.recordOnly = recordOnly;
        log.info("AutoSpotting AWS events: enabled={} record-only={} queue={} prodProfile={}", enabled, recordOnly,
                this.queueUrl.isEmpty() ? "blank" : queueName().orElse("unrecognised"), prodProfile);
    }

    public boolean enabled() {
        return enabled;
    }

    public String queueUrl() {
        return queueUrl;
    }

    /** True while events are only recorded: no reconcile hint, no action (Phase 22 shape). */
    public boolean recordOnly() {
        return recordOnly;
    }

    public boolean prodProfile() {
        return prodProfile;
    }

    /** The last path segment of the queue URL. */
    public Optional<String> queueName() {
        if (queueUrl.isEmpty()) {
            return Optional.empty();
        }
        int slash = queueUrl.lastIndexOf('/');
        String name = slash < 0 ? queueUrl : queueUrl.substring(slash + 1);
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * The environment suffix of the queue name, {@code ""} for prod and {@code "-uat"} for UAT; empty when the
     * URL is blank or the name is not one of ours. The forwarder role is {@code xamops-as-events-fwd-role{suffix}}.
     */
    public Optional<String> suffix() {
        return queueName().flatMap(name -> {
            Matcher m = QUEUE_NAME.matcher(name);
            if (!m.matches()) {
                return Optional.empty();
            }
            return Optional.of(m.group(1) == null ? "" : m.group(1));
        });
    }

    /** True when the queue is a production queue: the unsuffixed new queue or the legacy one. */
    public boolean namesProductionQueue() {
        return queueName().map(name -> LEGACY_QUEUE_NAME.equals(name)
                || suffix().map(String::isEmpty).orElse(false)).orElse(false);
    }

    /**
     * Why the listener and the policy sync must not run, or empty when they may. A blank URL, a name that is
     * not one of ours, or a production queue in a non-prod profile is refused.
     */
    public Optional<String> refusal() {
        if (queueUrl.isEmpty()) {
            return Optional.of("queue-url is blank");
        }
        if (queueName().isEmpty() || (suffix().isEmpty() && !LEGACY_QUEUE_NAME.equals(queueName().get()))) {
            return Optional.of("queue name is not xamops-autospotting-events{suffix}.fifo");
        }
        if (!prodProfile && namesProductionQueue()) {
            return Optional.of("a non-prod profile names the production queue");
        }
        return Optional.empty();
    }
}
