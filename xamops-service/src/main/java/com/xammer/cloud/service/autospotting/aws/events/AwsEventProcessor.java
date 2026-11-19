package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.AwsSpotFailureMatcher;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-message processing of the AWS event pipeline (design-aws.md §9.6 steps 1-7, plan-phase-22.md 22.3).
 * Events are hints, polling is the truth: nothing here mutates a cloud, and in the record-only mode this
 * phase ships in ({@link AwsEventProperties#recordOnly()}) not even a reconcile hint is set.
 *
 * <ol>
 *   <li>Parse the envelope. Unparseable is {@link Outcome#RETRY}: the message stays and reaches the DLQ.</li>
 *   <li>Resolve the account by the body's account id. None, several, or not CONNECTED is
 *       {@link Outcome#UNKNOWN_ACCOUNT}: acknowledged and counted.</li>
 *   <li>Sender check ({@link EventSenderVerifier}). A mismatch is dropped, acknowledged and counted on
 *       {@code autospotting.events.security{reason=sender_mismatch}}; no inbox row is written, so a spoofer
 *       cannot reserve a real event id. An undecidable sender is {@link Outcome#RETRY}.</li>
 *   <li>Resolve the group (index, then {@link InstanceGroupLookup}); an AWS read failure is a retry.</li>
 *   <li>In one transaction: inbox insert {@code ON CONFLICT DO NOTHING} (zero rows is a duplicate,
 *       acknowledged) and the event row. A transient DB error rolls both back and the message is retried,
 *       so the retry is not seen as a duplicate.</li>
 * </ol>
 */
@Component
public class AwsEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(AwsEventProcessor.class);
    /** The reconciler checks this long after a warning, once Auto Scaling has had time to replace (§11.1). */
    static final Duration INTERRUPTION_HINT_DELAY = Duration.ofMinutes(3);

    /** What to do with the SQS message afterwards. */
    public enum Outcome {
        APPLIED(true, "applied"),
        DUPLICATE(true, "duplicate"),
        UNMANAGED(true, "unmanaged"),
        UNKNOWN_TYPE(true, "unknown_type"),
        UNKNOWN_ACCOUNT(true, "unknown_account"),
        SENDER_MISMATCH(true, "sender_mismatch"),
        UNPARSEABLE(false, "unparseable"),
        RETRY(false, "retry");

        private final boolean acknowledge;
        private final String metric;

        Outcome(boolean acknowledge, String metric) {
            this.acknowledge = acknowledge;
            this.metric = metric;
        }

        /** True when the message is deleted; false leaves it for SQS to redeliver (and finally dead-letter). */
        public boolean acknowledge() {
            return acknowledge;
        }

        public String metric() {
            return metric;
        }
    }

    private final ObjectMapper mapper;
    private final CloudAccountRepository accounts;
    private final EventSenderVerifier sender;
    private final InstanceGroupLookup groupLookup;
    private final AwsEventStore store;
    private final AwsEventProperties properties;
    private final SwitchService switches;
    private final AutoSpottingMetrics metrics;
    private final TransactionTemplate tx;

    public AwsEventProcessor(@Qualifier("objectMapper") ObjectMapper mapper, CloudAccountRepository accounts, EventSenderVerifier sender,
                             InstanceGroupLookup groupLookup, AwsEventStore store, AwsEventProperties properties,
                             SwitchService switches, AutoSpottingMetrics metrics,
                             PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.sender = sender;
        this.groupLookup = groupLookup;
        this.store = store;
        this.properties = properties;
        this.switches = switches;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Process one message body with the SQS {@code SenderId}; counts the outcome and never throws. */
    public Outcome process(String body, String senderId) {
        Outcome outcome;
        try {
            outcome = processInternal(body, senderId);
        } catch (RuntimeException e) {
            log.warn("AutoSpotting event left for retry: {}", e.toString());
            outcome = Outcome.RETRY;
        }
        if (outcome == Outcome.SENDER_MISMATCH) {
            metrics.eventSecurity(Provider.AWS, "sender_mismatch");
        } else {
            metrics.event(Provider.AWS, outcome.metric());
        }
        return outcome;
    }

    private Outcome processInternal(String body, String senderId) {
        Optional<AwsEventEnvelope> parsed = AwsEventEnvelope.parse(mapper, body);
        if (parsed.isEmpty()) {
            return Outcome.UNPARSEABLE;
        }
        AwsEventEnvelope event = parsed.get();
        List<CloudAccount> matches = accounts.findByAwsAccountId(event.account()).stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()) && "CONNECTED".equals(a.getStatus()))
                .toList();
        if (matches.size() != 1) {
            return Outcome.UNKNOWN_ACCOUNT;
        }
        CloudAccount account = matches.get(0);
        switch (sender.verify(account, senderId, event.region())) {
            case MISMATCH:
                return Outcome.SENDER_MISMATCH;
            case RETRY:
                return Outcome.RETRY;
            default:
                break;
        }
        if (store.inboxSeen(event.id())) {
            return Outcome.DUPLICATE;
        }
        boolean known = isKnown(event.detailType());
        Optional<UUID> group = known ? resolveGroup(account, event) : Optional.empty();
        Instant now = Instant.now();
        boolean hint = known && group.isPresent() && !properties.recordOnly() && hintsAllowed(account);
        Outcome result = tx.execute(status -> {
            String inboxOutcome = !known || group.isEmpty() ? "UNMANAGED" : "APPLIED";
            if (!store.insertInbox(event.id(), account.getId(), event.detailType(), inboxOutcome, now)) {
                return Outcome.DUPLICATE;
            }
            if (!known) {
                return Outcome.UNKNOWN_TYPE;
            }
            if (group.isEmpty()) {
                return Outcome.UNMANAGED;
            }
            apply(event, account, group.get(), hint, now);
            return Outcome.APPLIED;
        });
        return result == null ? Outcome.RETRY : result;
    }

    static boolean isKnown(String detailType) {
        return AwsEventEnvelope.INTERRUPTION.equals(detailType) || AwsEventEnvelope.REBALANCE.equals(detailType)
                || AwsEventEnvelope.LAUNCH_OK.equals(detailType) || AwsEventEnvelope.LAUNCH_FAILED.equals(detailType)
                || AwsEventEnvelope.TERMINATE_OK.equals(detailType);
    }

    /** Group by the body's group name, else the instance index, else {@link InstanceGroupLookup}. */
    private Optional<UUID> resolveGroup(CloudAccount account, AwsEventEnvelope event) {
        String region = event.region();
        String name = event.detailText("AutoScalingGroupName");
        if (name != null) {
            return store.managedGroup(account.getId(), region + "/" + name);
        }
        String instanceId = instanceIdOf(event);
        if (instanceId == null) {
            return Optional.empty();
        }
        Optional<UUID> indexed = store.groupForInstance(account.getId(), instanceId);
        if (indexed.isPresent()) {
            return indexed;
        }
        Optional<String> looked = groupLookup.groupName(account, region, instanceId);
        if (looked.isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> group = store.managedGroup(account.getId(), region + "/" + looked.get());
        group.ifPresent(id -> store.rememberInstance(instanceId, id, region, null, Instant.now()));
        return group;
    }

    static String instanceIdOf(AwsEventEnvelope event) {
        String id = event.detailText("instance-id");
        return id != null ? id : event.detailText("EC2InstanceId");
    }

    /** Switches matrix, listener row (design-core.md §6.3): an account switch or a freeze records only. */
    private boolean hintsAllowed(CloudAccount account) {
        SwitchState state = switches.current();
        return !(state.switchStoreUnreadable() || state.freezeEngaged() || state.accountEngaged(account.getId()));
    }

    private void apply(AwsEventEnvelope event, CloudAccount account, UUID groupId, boolean hint, Instant now) {
        Instant occurred = event.time() == null ? now : event.time();
        String instanceId = instanceIdOf(event);
        String type = event.detailType();
        if (AwsEventEnvelope.INTERRUPTION.equals(type)) {
            store.insertEvent(groupId, "INTERRUPTION_WARNING", event.id(), instanceId, null, null, "spot", null,
                    occurred, now);
            metrics.interruption(Provider.AWS, event.region(), "event");
            if (hint) {
                store.setHint(groupId, now.plus(INTERRUPTION_HINT_DELAY));
            }
        } else if (AwsEventEnvelope.REBALANCE.equals(type)) {
            store.insertEvent(groupId, "REBALANCE_RECOMMENDATION", event.id(), instanceId, null, null, "spot", null,
                    occurred, now);
        } else if (AwsEventEnvelope.LAUNCH_OK.equals(type)) {
            // Null-safe: an On-Demand launch has no lifecycle value, and the index may not know the instance yet.
            String lifecycle = instanceId == null ? null : store.lifecycleOf(instanceId);
            store.insertEvent(groupId, "LAUNCH", activityKey(event), instanceId, null, zoneOf(event), lifecycle, null,
                    occurred, now);
        } else if (AwsEventEnvelope.LAUNCH_FAILED.equals(type)) {
            // Classified from the status message (design-aws.md §6.2): an On-Demand failure is never SPOT_CAPACITY.
            String failureClass = AwsSpotFailureMatcher.classify(event.detailText("StatusMessage"),
                    event.detailText("Description")).name();
            store.insertEvent(groupId, "LAUNCH_FAILED", activityKey(event), instanceId, null, zoneOf(event), null,
                    failureClass, occurred, now);
            if (hint) {
                store.setHint(groupId, now);
            }
        } else if (AwsEventEnvelope.TERMINATE_OK.equals(type)) {
            store.insertEvent(groupId, "TERMINATE", activityKey(event), instanceId, null, zoneOf(event), null, null,
                    occurred, now);
        }
        store.touchLastEvent(account.getId(), event.region(), now);
    }

    /** The Auto Scaling activity id, so an event and a poll of the same fact collapse (core §8.1). */
    private static String activityKey(AwsEventEnvelope event) {
        String activity = event.detailText("ActivityId");
        return activity != null ? activity : event.id();
    }

    private static String zoneOf(AwsEventEnvelope event) {
        JsonNode details = event.detail().get("Details");
        if (details == null || !details.isObject()) {
            return null;
        }
        JsonNode zone = details.get("Availability Zone");
        return zone == null || zone.isNull() ? null : zone.asText(null);
    }
}
