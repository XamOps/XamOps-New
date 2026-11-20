package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The AWS event listener (design-aws.md §9.6, FR-EVT-04, plan-phase-22.md task 22.3). One long-poll loop on the
 * {@code spotSqsListener} executor (thread {@code spot-sched-sqs}): up to 10 messages, 20 s wait, each handed
 * to {@link AwsEventProcessor}. A message is deleted when the processor says to; otherwise it stays, SQS
 * redelivers it after the visibility timeout, and after five receives it moves to the DLQ.
 *
 * <p><strong>Guards.</strong>
 * <ul>
 *   <li>{@code autospotting.aws.events.enabled=false} (code default): no loop.</li>
 *   <li>{@link AwsEventProperties#refusal()}: a blank URL, an unknown queue name, or a production queue in a
 *       non-prod profile logs a warning and never consumes (FR-SPN-10, AC-SPN-11).</li>
 *   <li>Switches (design-core.md §6.3, "AWS SQS listener consume"): the global property or switch, and the AWS
 *       cloud property or switch, stop polling. An account switch, the freeze or an unreadable store keep
 *       polling and record only, which {@link AwsEventProcessor} applies.</li>
 *   <li>{@code scheduling.enabled=false}: no loop.</li>
 * </ul>
 *
 * <p><strong>Order.</strong> FIFO delivers a message group in order. When a message is left for retry, the later
 * messages of the same group in the batch are left too, so one account's poison message delays only that
 * account's group (AC-EVT-02).
 */
@Component
public class AutoSpottingEventListener {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingEventListener.class);

    static final int MAX_MESSAGES = 10;
    static final int WAIT_SECONDS = 20;
    static final long IDLE_MILLIS = 30_000L;
    static final long MAX_BACKOFF_MILLIS = 300_000L;
    static final long BASE_BACKOFF_MILLIS = 5_000L;

    private final SqsClient sqs;
    private final AwsEventProperties properties;
    private final AwsEventProcessor processor;
    private final ThreadPoolTaskExecutor executor;
    private final SwitchService switches;
    private final AutoSpottingSwitchProperties switchProperties;
    private final SchedulingEnvironmentConfig environment;
    private final boolean globalEnabled;

    private volatile boolean running;

    public AutoSpottingEventListener(SqsClient sqs, AwsEventProperties properties, AwsEventProcessor processor,
                                     @Qualifier("spotSqsListener") ThreadPoolTaskExecutor executor,
                                     SwitchService switches, AutoSpottingSwitchProperties switchProperties,
                                     SchedulingEnvironmentConfig environment,
                                     @Value("${autospotting.enabled:false}") boolean globalEnabled) {
        this.sqs = sqs;
        this.properties = properties;
        this.processor = processor;
        this.executor = executor;
        this.switches = switches;
        this.switchProperties = switchProperties;
        this.environment = environment;
        this.globalEnabled = globalEnabled;
    }

    /** Start the loop once the context is ready, unless the pipeline is off or refused. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("AutoSpotting AWS event listener off (autospotting.aws.events.enabled=false).");
            return;
        }
        if (properties.refusal().isPresent()) {
            log.warn("AutoSpotting AWS event listener will not consume: {}. Queue URL: {}.",
                    properties.refusal().get(), properties.queueUrl().isEmpty() ? "blank" : properties.queueUrl());
            return;
        }
        if (!environment.isSchedulingEnabled()) {
            log.info("AutoSpotting AWS event listener off (scheduling.enabled=false).");
            return;
        }
        running = true;
        try {
            executor.execute(this::loop);
            log.info("AutoSpotting AWS event listener started on queue {} (record-only={}).",
                    properties.queueName().orElse("?"), properties.recordOnly());
        } catch (TaskRejectedException e) {
            running = false;
            log.warn("AutoSpotting AWS event listener could not start: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    /** True while the loop is meant to run. */
    public boolean running() {
        return running;
    }

    private void loop() {
        long failures = 0;
        while (running) {
            try {
                int handled = pollOnce();
                failures = 0;
                if (handled < 0 && !pause(IDLE_MILLIS)) {
                    return;
                }
            } catch (RuntimeException e) {
                // A missing queue or a credential error: wait and try again, never spin and never die.
                failures++;
                long wait = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << Math.min(failures, 6));
                log.warn("AutoSpotting AWS event poll failed ({}); retrying in {} s.", e.toString(), wait / 1000);
                if (!pause(wait)) {
                    return;
                }
            }
        }
    }

    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Why polling is stopped by a switch right now, or null when it may poll (design-core.md §6.3). */
    String stoppedBy(SwitchState state) {
        if (!globalEnabled || state.engagedScopeKeys().contains(SwitchState.GLOBAL)) {
            return "global";
        }
        if (!switchProperties.cloudEnabled(true) || state.engagedScopeKeys().contains(SwitchState.cloudKey(true))) {
            return "cloud";
        }
        return null;
    }

    /**
     * One receive and process cycle. Returns the number of messages received, or {@code -1} when polling is
     * stopped (refused queue, disabled, or a switch). Public so the integration test drives it with a stubbed
     * SQS client instead of a thread.
     */
    public int pollOnce() {
        if (!properties.enabled() || properties.refusal().isPresent()) {
            return -1;
        }
        if (stoppedBy(switches.current()) != null) {
            return -1;
        }
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(MAX_MESSAGES)
                .waitTimeSeconds(WAIT_SECONDS)
                .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
                .build()).messages();
        if (messages.isEmpty()) {
            return 0;
        }
        TenantContext.setCurrentTenant(environment.getCurrentTenantId());
        try {
            Set<String> blockedGroups = new HashSet<>();
            for (Message message : messages) {
                String group = message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID);
                if (group != null && blockedGroups.contains(group)) {
                    continue; // keep the group's order: leave it for redelivery behind the failed message
                }
                String senderId = message.attributes().get(MessageSystemAttributeName.SENDER_ID);
                AwsEventProcessor.Outcome outcome = processor.process(message.body(), senderId);
                if (outcome.acknowledge()) {
                    delete(message);
                } else if (group != null) {
                    blockedGroups.add(group);
                }
            }
        } finally {
            TenantContext.clear();
        }
        return messages.size();
    }

    private void delete(Message message) {
        try {
            sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                    .receiptHandle(message.receiptHandle()).build());
        } catch (RuntimeException e) {
            // At-least-once: the inbox makes the redelivery a no-op.
            log.warn("AutoSpotting AWS event {} not deleted: {}", message.messageId(), e.toString());
        }
    }
}
