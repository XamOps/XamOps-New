package com.xammer.cloud.service.autospotting.aws.events;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory SQS FIFO pair for the tests (NO real AWS, no LocalStack, no queue created anywhere): a source
 * queue with a dead-letter queue and a redrive policy. It models what the listener and the DLQ service rely on:
 * <ul>
 *   <li>messages of one group are delivered in order, and a group with a message in flight is not delivered
 *       again until {@link #expireVisibility()} (so a poison message blocks only its own group);</li>
 *   <li>a send with a deduplication id seen before is dropped;</li>
 *   <li>the receive count rises on each receive, and a message received {@link #maxReceiveCount} times moves to
 *       the DLQ on the next visibility expiry;</li>
 *   <li>{@code SenderId} is whatever the test sets on the message.</li>
 * </ul>
 * Every unlisted SQS call throws, so a test cannot reach an API it did not intend to.
 */
public final class FakeSqsClient implements SqsClient {

    public static final String QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/000000000000/xamops-autospotting-events-uat.fifo";
    public static final String DLQ_URL = "https://sqs.ap-south-1.amazonaws.com/000000000000/xamops-autospotting-events-uat-dlq.fifo";
    static final String QUEUE_ARN = "arn:aws:sqs:ap-south-1:000000000000:xamops-autospotting-events-uat.fifo";
    static final String DLQ_ARN = "arn:aws:sqs:ap-south-1:000000000000:xamops-autospotting-events-uat-dlq.fifo";

    /** One message with its delivery state. */
    public static final class Msg {
        public final String id = UUID.randomUUID().toString();
        public final String body;
        public final String group;
        public final String dedup;
        public final String sender;
        public int receives;
        boolean inFlight;
        String receipt;

        Msg(String body, String group, String dedup, String sender) {
            this.body = body;
            this.group = group;
            this.dedup = dedup;
            this.sender = sender;
        }
    }

    public int maxReceiveCount = 5;
    public String policy;
    public final List<Msg> queue = new ArrayList<>();
    public final List<Msg> dlq = new ArrayList<>();
    public final List<String> calls = new ArrayList<>();
    public final List<SendMessageRequest> sent = new ArrayList<>();
    /** When set, StartMessageMoveTask is refused as unsupported, so the service falls back to re-sending. */
    public boolean moveTaskUnsupported;
    public boolean moveTaskRunning;
    public String moveTaskStatus = "RUNNING";
    public int moveTasksStarted;
    private final Set<String> seenDedup = new HashSet<>();

    /** Put a message on the queue as if the forwarder sent it. */
    public void send(String body, String group, String dedup, String sender) {
        if (dedup != null && !seenDedup.add(dedup)) {
            return;
        }
        queue.add(new Msg(body, group, dedup, sender));
    }

    /** Visibility timeout elapsed: every in-flight message becomes visible again, or dead-letters at the limit. */
    public void expireVisibility() {
        List<Msg> moved = new ArrayList<>();
        for (Msg m : queue) {
            if (m.inFlight) {
                m.inFlight = false;
                if (m.receives >= maxReceiveCount) {
                    moved.add(m);
                }
            }
        }
        queue.removeAll(moved);
        dlq.addAll(moved);
        moved.forEach(m -> m.receives = 0);
    }

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        calls.add("receive:" + request.queueUrl());
        List<Msg> source = DLQ_URL.equals(request.queueUrl()) ? dlq : queue;
        boolean peek = request.visibilityTimeout() != null && request.visibilityTimeout() == 0;
        int max = request.maxNumberOfMessages() == null ? 1 : request.maxNumberOfMessages();
        Set<String> groupsInFlight = new HashSet<>();
        for (Msg m : source) {
            if (m.inFlight) {
                groupsInFlight.add(m.group);
            }
        }
        List<Message> out = new ArrayList<>();
        for (Msg m : source) {
            if (out.size() >= max) {
                break;
            }
            // A group with a message in flight at the start of the call is blocked; a free group delivers in order.
            if (m.inFlight || groupsInFlight.contains(m.group)) {
                continue;
            }
            m.receives++;
            if (!peek) {
                m.inFlight = true;
            }
            m.receipt = UUID.randomUUID().toString();
            Map<MessageSystemAttributeName, String> attrs = new HashMap<>();
            attrs.put(MessageSystemAttributeName.MESSAGE_GROUP_ID, m.group);
            attrs.put(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, String.valueOf(m.receives));
            attrs.put(MessageSystemAttributeName.SENT_TIMESTAMP, "1760000000000");
            if (m.sender != null) {
                attrs.put(MessageSystemAttributeName.SENDER_ID, m.sender);
            }
            out.add(Message.builder().messageId(m.id).body(m.body).receiptHandle(m.receipt).attributes(attrs).build());
        }
        return ReceiveMessageResponse.builder().messages(out).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
        calls.add("delete:" + request.queueUrl());
        List<Msg> source = DLQ_URL.equals(request.queueUrl()) ? dlq : queue;
        source.removeIf(m -> request.receiptHandle().equals(m.receipt));
        return DeleteMessageResponse.builder().build();
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        calls.add("send:" + request.queueUrl());
        sent.add(request);
        send(request.messageBody(), request.messageGroupId(), request.messageDeduplicationId(), "AROAFAKEFORWARDER:replay");
        return SendMessageResponse.builder().messageId(UUID.randomUUID().toString()).build();
    }

    @Override
    public GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest request) {
        calls.add("getAttributes:" + request.queueUrl());
        Map<QueueAttributeName, String> attrs = new LinkedHashMap<>();
        boolean dlqQueue = DLQ_URL.equals(request.queueUrl());
        for (QueueAttributeName n : request.attributeNames()) {
            if (n == QueueAttributeName.QUEUE_ARN) {
                attrs.put(n, dlqQueue ? DLQ_ARN : QUEUE_ARN);
            } else if (n == QueueAttributeName.POLICY && policy != null) {
                attrs.put(n, policy);
            } else if (n == QueueAttributeName.REDRIVE_POLICY && !dlqQueue) {
                attrs.put(n, "{\"deadLetterTargetArn\":\"" + DLQ_ARN + "\",\"maxReceiveCount\":" + maxReceiveCount + "}");
            } else if (n == QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES) {
                attrs.put(n, String.valueOf(dlqQueue ? dlq.size() : queue.size()));
            }
        }
        return GetQueueAttributesResponse.builder().attributes(attrs).build();
    }

    @Override
    public SetQueueAttributesResponse setQueueAttributes(SetQueueAttributesRequest request) {
        calls.add("setAttributes:" + request.queueUrl());
        policy = request.attributes().get(QueueAttributeName.POLICY);
        return SetQueueAttributesResponse.builder().build();
    }

    @Override
    public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
        calls.add("getQueueUrl:" + request.queueName());
        return GetQueueUrlResponse.builder().queueUrl(request.queueName().contains("dlq") ? DLQ_URL : QUEUE_URL).build();
    }

    @Override
    public ListMessageMoveTasksResponse listMessageMoveTasks(ListMessageMoveTasksRequest request) {
        calls.add("listMoveTasks");
        if (!moveTaskRunning) {
            return ListMessageMoveTasksResponse.builder().build();
        }
        return ListMessageMoveTasksResponse.builder().results(
                software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResultEntry.builder()
                        .status(moveTaskStatus).build()).build();
    }

    @Override
    public software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskResponse startMessageMoveTask(
            software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest request) {
        calls.add("startMoveTask");
        if (moveTaskUnsupported) {
            throw software.amazon.awssdk.services.sqs.model.SqsException.builder().statusCode(400)
                    .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                            .errorCode("InvalidParameterValue").build()).build();
        }
        moveTasksStarted++;
        // The service moves every DLQ message back to the source queue, keeping group and body.
        for (Msg m : new ArrayList<>(dlq)) {
            queue.add(new Msg(m.body, m.group, null, m.sender));
        }
        dlq.clear();
        return software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskResponse.builder()
                .taskHandle("task-" + moveTasksStarted).build();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
    }
}
