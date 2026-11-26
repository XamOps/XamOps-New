package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResultEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dead-letter queue view and replay (design-aws.md §9.8, plan-phase-22.md task 22.5, core §6.6).
 *
 * <ul>
 *   <li>{@link #summary()}: the depth and up to ten messages, each as detail type, account, group, sent time
 *       and receive count. <strong>Never a message body.</strong> The messages are peeked with a zero
 *       visibility timeout, so the listing leaves them where they are.</li>
 *   <li>{@link #redrive}: SQS {@code StartMessageMoveTask} from the DLQ back to the source queue. One at a time
 *       per queue: a running task is 409 {@code REDRIVE_IN_PROGRESS}. If SQS rejects the move task for a FIFO
 *       queue (V-AWS-14, ap-south-1), the service receives from the DLQ and re-sends each message to the
 *       source queue with its original group id and a fresh deduplication id, then deletes it from the DLQ.
 *       Either way a replay is safe: the inbox ({@code autospotting_event_inbox}) turns a message that was
 *       already applied into a duplicate.</li>
 * </ul>
 * The environment guard applies: a queue the listener refuses (blank, or production in a non-prod profile) is
 * never listed or replayed. Each redrive writes an {@code OPERATOR} audit record with its reason.
 */
@Service
public class AwsEventDlqService {

    private static final Logger log = LoggerFactory.getLogger(AwsEventDlqService.class);
    static final int PEEK = 10;
    static final int RESEND_CAP = 1000;

    /** One dead-lettered message, without its body. */
    public record Entry(String messageId, String detailType, String account, String groupId, Instant sentAt,
                        int receiveCount) {
    }

    /** The DLQ at a glance. {@code configured} is false when no queue is set or the guard refuses it. */
    public record Summary(boolean configured, String queue, long depth, List<Entry> messages, boolean redriveRunning) {
    }

    /** What a redrive did: {@code MOVE_TASK} (SQS moves them) or {@code RESEND} (re-sent one by one). */
    public record RedriveResult(String mode, String taskHandle, int moved) {
    }

    private final SqsClient sqs;
    private final AwsEventProperties properties;
    private final ObjectMapper mapper;
    private final AutoSpottingAuditWriter audit;
    private final TransactionTemplate auditTx;
    private final AtomicBoolean resending = new AtomicBoolean(false);

    public AwsEventDlqService(SqsClient sqs, AwsEventProperties properties,
                              @Qualifier("objectMapper") ObjectMapper mapper,
                              AutoSpottingAuditWriter audit, PlatformTransactionManager txManager) {
        this.sqs = sqs;
        this.properties = properties;
        this.mapper = mapper;
        this.audit = audit;
        // The audit writer is MANDATORY and redrive runs outside any transaction (D-23b-04), so the row gets its own.
        this.auditTx = new TransactionTemplate(txManager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The DLQ summary; an unconfigured or refused queue reads as {@code configured=false}. */
    public Summary summary() {
        if (!properties.enabled() || properties.refusal().isPresent()) {
            return new Summary(false, null, 0, List.of(), false);
        }
        try {
            Dlq dlq = dlq();
            long depth = Long.parseLong(attribute(dlq.url(), QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
            List<Entry> entries = new ArrayList<>();
            if (depth > 0) {
                for (Message m : sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlq.url())
                        .maxNumberOfMessages(PEEK).visibilityTimeout(0).waitTimeSeconds(0)
                        .messageSystemAttributeNames(MessageSystemAttributeName.ALL).build()).messages()) {
                    entries.add(entry(m));
                }
            }
            return new Summary(true, dlq.name(), depth, entries, moveTaskRunning(dlq.arn()) || resending.get());
        } catch (AutoSpottingApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("DLQ summary unavailable: {}", e.toString());
            throw new AutoSpottingApiException(HttpStatus.BAD_GATEWAY, "DLQ_UNAVAILABLE",
                    "The dead-letter queue could not be read.", true, null, null);
        }
    }

    /** Replay the DLQ into the source queue. */
    public RedriveResult redrive(String reason, Actor actor) {
        if (!properties.enabled() || properties.refusal().isPresent()) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "EVENTS_QUEUE_UNAVAILABLE",
                    "The AutoSpotting events queue is not configured for this environment.");
        }
        RedriveResult result;
        try {
            Dlq dlq = dlq();
            String sourceArn = attribute(properties.queueUrl(), QueueAttributeName.QUEUE_ARN, null);
            if (moveTaskRunning(dlq.arn()) || resending.get()) {
                throw inProgress();
            }
            result = start(dlq, sourceArn);
        } catch (AutoSpottingApiException e) {
            throw e;
        } catch (RuntimeException e) {
            writeAudit(actor, reason, "FAILED", "REDRIVE_FAILED", null);
            log.warn("DLQ redrive failed: {}", e.toString());
            throw new AutoSpottingApiException(HttpStatus.BAD_GATEWAY, "DLQ_UNAVAILABLE",
                    "The dead-letter queue could not be replayed.", true, null, null);
        }
        writeAudit(actor, reason, "SUCCESS", null, result);
        return result;
    }

    private RedriveResult start(Dlq dlq, String sourceArn) {
        try {
            String handle = sqs.startMessageMoveTask(StartMessageMoveTaskRequest.builder()
                    .sourceArn(dlq.arn()).destinationArn(sourceArn).build()).taskHandle();
            return new RedriveResult("MOVE_TASK", handle, 0);
        } catch (SqsException e) {
            // A 400 means the move task cannot run for this queue pair (V-AWS-14). Only the status is read, never
            // the error body (FR-SEC-06). A missing queue would fail the re-send too, so nothing is hidden.
            if (com.xammer.cloud.service.autospotting.aws.AwsErrors.classify(e)
                    == com.xammer.cloud.service.autospotting.spi.ErrorKind.VALIDATION) {
                // V-AWS-14: message move tasks do not support this FIFO pair. Re-send instead.
                return resend(dlq);
            }
            throw e;
        }
    }

    /** Receive from the DLQ and re-send with the original group id and a fresh deduplication id. */
    private RedriveResult resend(Dlq dlq) {
        if (!resending.compareAndSet(false, true)) {
            throw inProgress();
        }
        try {
            int moved = 0;
            while (moved < RESEND_CAP) {
                List<Message> batch = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlq.url())
                        .maxNumberOfMessages(10).waitTimeSeconds(0)
                        .messageSystemAttributeNames(MessageSystemAttributeName.ALL).build()).messages();
                if (batch.isEmpty()) {
                    break;
                }
                for (Message m : batch) {
                    String group = m.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID);
                    sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl())
                            .messageBody(m.body()).messageGroupId(group == null ? "replay" : group)
                            .messageDeduplicationId(UUID.randomUUID().toString()).build());
                    sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlq.url())
                            .receiptHandle(m.receiptHandle()).build());
                    moved++;
                }
            }
            return new RedriveResult("RESEND", null, moved);
        } finally {
            resending.set(false);
        }
    }

    private boolean moveTaskRunning(String dlqArn) {
        try {
            for (ListMessageMoveTasksResultEntry t : sqs.listMessageMoveTasks(
                    ListMessageMoveTasksRequest.builder().sourceArn(dlqArn).maxResults(5).build()).results()) {
                String status = t.status();
                if ("RUNNING".equals(status) || "CANCELLING".equals(status)) {
                    return true;
                }
            }
            return false;
        } catch (SqsException e) {
            // A FIFO pair that cannot list tasks has none running; the resend flag guards the other mode.
            return false;
        }
    }

    private Entry entry(Message m) {
        String detailType = null;
        String account = null;
        Optional<AwsEventEnvelope> parsed = AwsEventEnvelope.parse(mapper, m.body());
        if (parsed.isPresent()) {
            detailType = parsed.get().detailType();
            account = parsed.get().account();
        }
        Map<MessageSystemAttributeName, String> attrs = m.attributes();
        String sent = attrs.get(MessageSystemAttributeName.SENT_TIMESTAMP);
        String count = attrs.get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        return new Entry(m.messageId(), detailType, account, attrs.get(MessageSystemAttributeName.MESSAGE_GROUP_ID),
                sent == null ? null : Instant.ofEpochMilli(Long.parseLong(sent)),
                count == null ? 0 : Integer.parseInt(count));
    }

    private record Dlq(String url, String arn, String name) {
    }

    /** The DLQ named by the source queue's redrive policy. */
    private Dlq dlq() {
        String policy = attribute(properties.queueUrl(), QueueAttributeName.REDRIVE_POLICY, null);
        if (policy == null) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "EVENTS_QUEUE_UNAVAILABLE",
                    "The events queue has no dead-letter queue.");
        }
        try {
            JsonNode node = mapper.readTree(policy);
            String arn = node.path("deadLetterTargetArn").asText("");
            String name = arn.substring(arn.lastIndexOf(':') + 1);
            if (name.isBlank()) {
                throw new IllegalStateException("redrive policy has no dead-letter target");
            }
            String url = sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
            return new Dlq(url, arn, name);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("redrive policy unreadable", e);
        }
    }

    private String attribute(String queueUrl, QueueAttributeName name, String fallback) {
        String v = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(name).build()).attributes().get(name);
        return v == null ? fallback : v;
    }

    private static AutoSpottingApiException inProgress() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "REDRIVE_IN_PROGRESS",
                "A replay of the dead-letter queue is already running.");
    }

    private void writeAudit(Actor actor, String reason, String outcome, String errorCode, RedriveResult result) {
        try {
            AuditRecord.Builder b = AuditRecord.builder("OPERATOR_EVENTS_DLQ", actor, "DLQ_REDRIVE", outcome)
                    .provider("AWS")
                    .errorCode(errorCode)
                    .details("{\"reason\":" + mapper.writeValueAsString(reason == null ? "" : reason)
                            + (result == null ? "" : ",\"mode\":\"" + result.mode() + "\",\"moved\":" + result.moved())
                            + "}");
            AuditRecord record = b.build();
            auditTx.executeWithoutResult(status -> audit.write(record));
        } catch (Exception e) {
            log.warn("DLQ redrive audit not written: {}", e.toString());
        }
    }
}
