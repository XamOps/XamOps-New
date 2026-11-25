package com.xammer.cloud.xampulse.service.setup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.xampulse.dto.XpRetention;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retention rules: validation and the two ways they reach S3. In CREATE mode they become stack parameters (the
 * stack owns the bucket). In EXISTING mode they become a ready-made {@code put-bucket-lifecycle-configuration}
 * document scoped to the xamops-xampulse/ prefix that the customer applies themselves.
 */
@Component("xpRetentionPolicyService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class RetentionPolicyService {

    public static final String PREFIX = "xamops-xampulse/";
    /** Standard-IA and Glacier IR bill a 128 KB minimum per object, so smaller objects stay in Standard. */
    public static final long MIN_TRANSITION_SIZE = 131072L;
    public static final int ABORT_MULTIPART_DAYS = 7;
    public static final int NONCURRENT_DAYS = 30;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final SetupPlanValidator validator;

    public RetentionPolicyService(SetupPlanValidator validator) {
        this.validator = validator;
    }

    public void validateOrThrow(XpRetention r) {
        List<String> errors = new ArrayList<>();
        validator.retention(r, errors);
        if (!errors.isEmpty()) {
            throw new XamPulseException("XP_INVALID_REQUEST", 400, errors.get(0), Map.of("errors", errors));
        }
    }

    /** CloudFormation parameter values used only when the stack creates the bucket. */
    public Map<String, String> createModeParameters(XpRetention r) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("IaAfterDays", Integer.toString(r.iaAfterDays()));
        p.put("GlacierIrAfterDays", Integer.toString(r.glacierIrAfterDays()));
        p.put("ExpireAfterDays", Integer.toString(r.expireAfterDays()));
        return p;
    }

    /**
     * Lifecycle configuration for an existing bucket. Mirrors the rules the CREATE-mode template builds: tiering rule
     * with the object-size filter, and a separate expiry rule without it (so small objects still expire).
     */
    public String lifecycleJson(XpRetention r) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode rules = root.putArray("Rules");

        ObjectNode tier = rules.addObject();
        tier.put("ID", "XamPulseTiering");
        tier.put("Status", "Enabled");
        ObjectNode and = tier.putObject("Filter").putObject("And");
        and.put("Prefix", PREFIX);
        and.put("ObjectSizeGreaterThan", MIN_TRANSITION_SIZE);
        ArrayNode transitions = tier.putArray("Transitions");
        transitions.addObject().put("Days", r.iaAfterDays()).put("StorageClass", "STANDARD_IA");
        transitions.addObject().put("Days", r.glacierIrAfterDays()).put("StorageClass", "GLACIER_IR");

        ObjectNode expiry = rules.addObject();
        expiry.put("ID", "XamPulseExpiry");
        expiry.put("Status", "Enabled");
        expiry.putObject("Filter").put("Prefix", PREFIX);
        expiry.putObject("Expiration").put("Days", r.expireAfterDays());
        expiry.putObject("NoncurrentVersionExpiration").put("NoncurrentDays", NONCURRENT_DAYS);
        expiry.putObject("AbortIncompleteMultipartUpload").put("DaysAfterInitiation", ABORT_MULTIPART_DAYS);
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
