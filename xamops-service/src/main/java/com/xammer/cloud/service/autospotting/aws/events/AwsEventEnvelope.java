package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The envelope the customer-account forwarder puts on the queue (xamops-autospotting-events-nested.yaml):
 * the EventBridge event with {@code account} set to the customer account id. {@link #parse} is the only way
 * in; a body that is not this shape is unparseable and stays on the queue until it reaches the DLQ
 * (design-aws.md §9.6 step 1).
 */
public record AwsEventEnvelope(String id, String detailType, String source, String account, String region,
                               Instant time, JsonNode detail) {

    static final int MAX_ID = 128;
    static final int MAX_DETAIL_TYPE = 80;
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{12}$");

    public static final String INTERRUPTION = "EC2 Spot Instance Interruption Warning";
    public static final String REBALANCE = "EC2 Instance Rebalance Recommendation";
    public static final String LAUNCH_OK = "EC2 Instance Launch Successful";
    public static final String LAUNCH_FAILED = "EC2 Instance Launch Unsuccessful";
    public static final String TERMINATE_OK = "EC2 Instance Terminate Successful";

    /** Parse {@code body}; empty when it is not JSON or lacks an id, detail-type, 12-digit account or detail. */
    public static Optional<AwsEventEnvelope> parse(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String id = text(root, "id");
        String detailType = text(root, "detail-type");
        String account = text(root, "account");
        JsonNode detail = root.get("detail");
        if (id == null || id.length() > MAX_ID || detailType == null || account == null
                || !ACCOUNT.matcher(account).matches() || detail == null || !detail.isObject()) {
            return Optional.empty();
        }
        if (detailType.length() > MAX_DETAIL_TYPE) {
            detailType = detailType.substring(0, MAX_DETAIL_TYPE);
        }
        Instant time = null;
        String rawTime = text(root, "time");
        if (rawTime != null) {
            try {
                time = Instant.parse(rawTime);
            } catch (DateTimeParseException ignored) {
                time = null;
            }
        }
        String region = text(root, "region");
        return Optional.of(new AwsEventEnvelope(id, detailType, text(root, "source"), account,
                region == null ? "" : region, time, detail));
    }

    /** A detail field as text, or null. */
    public String detailText(String field) {
        return text(detail, field);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
