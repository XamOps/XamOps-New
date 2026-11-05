package com.xammer.cloud.xampulse.service.setup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.xampulse.dto.XpRetention;
import com.xammer.cloud.xampulse.dto.XpSnippets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Copy-paste snippets for EXISTING-bucket mode. The stack never touches a bucket it did not create, so these are what
 * the customer applies to their own bucket: a policy statement set that lets the archive role in and keeps every other
 * principal (including the broad read of the XamOps connection role) out of the xamops-xampulse/ prefix, a KMS
 * grant for a customer key, and the lifecycle document.
 */
@Component("xpBucketSnippetService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class BucketSnippetService {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final RetentionPolicyService retention;

    public BucketSnippetService(RetentionPolicyService retention) {
        this.retention = retention;
    }

    public static String archiveRoleArn(String awsAccountId, String resourceSuffix) {
        return "arn:aws:iam::" + awsAccountId + ":role/XamOpsXamPulseArchiveRole" + (resourceSuffix == null ? "" : resourceSuffix);
    }

    public XpSnippets forExistingBucket(String bucket, String awsAccountId, String resourceSuffix, String kmsKeyArn, XpRetention r) {
        String role = archiveRoleArn(awsAccountId, resourceSuffix);
        return new XpSnippets(bucketPolicy(bucket, awsAccountId, role),
                kmsKeyArn == null || kmsKeyArn.isBlank() ? null : kmsGrant(role),
                retention.lifecycleJson(r));
    }

    /** Statements to merge into the bucket policy. Includes the deny for non-archive principals on the prefix. */
    public String bucketPolicy(String bucket, String awsAccountId, String roleArn) {
        String objects = "arn:aws:s3:::" + bucket + "/" + RetentionPolicyService.PREFIX + "*";
        String bucketArn = "arn:aws:s3:::" + bucket;
        String root = "arn:aws:iam::" + awsAccountId + ":root";

        ObjectNode doc = JSON.createObjectNode();
        doc.put("Version", "2012-10-17");
        ArrayNode st = doc.putArray("Statement");

        ObjectNode allow = st.addObject();
        allow.put("Sid", "AllowXamPulseArchiveRole");
        allow.put("Effect", "Allow");
        allow.putObject("Principal").put("AWS", roleArn);
        allow.putArray("Action").add("s3:PutObject").add("s3:GetObject").add("s3:AbortMultipartUpload");
        allow.put("Resource", objects);

        ObjectNode denyGet = st.addObject();
        denyGet.put("Sid", "DenyArchiveReadToOthers");
        denyGet.put("Effect", "Deny");
        denyGet.put("Principal", "*");
        denyGet.put("Action", "s3:GetObject");
        denyGet.put("Resource", objects);
        denyGet.putObject("Condition").putObject("ArnNotEquals").putArray("aws:PrincipalArn").add(roleArn).add(root);

        ObjectNode denyList = st.addObject();
        denyList.put("Sid", "DenyArchiveListToOthers");
        denyList.put("Effect", "Deny");
        denyList.put("Principal", "*");
        denyList.put("Action", "s3:ListBucket");
        denyList.put("Resource", bucketArn);
        ObjectNode cond = denyList.putObject("Condition");
        cond.putObject("StringLike").put("s3:prefix", RetentionPolicyService.PREFIX + "*");
        cond.putObject("ArnNotEquals").putArray("aws:PrincipalArn").add(roleArn).add(root);
        return write(doc);
    }

    /** Key policy statement (or grant equivalent) for a customer-managed key. */
    public String kmsGrant(String roleArn) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("Version", "2012-10-17");
        ObjectNode s = doc.putArray("Statement").addObject();
        s.put("Sid", "AllowXamPulseArchiveRole");
        s.put("Effect", "Allow");
        s.putObject("Principal").put("AWS", roleArn);
        s.putArray("Action").add("kms:GenerateDataKey").add("kms:Decrypt");
        s.put("Resource", "*");
        return write(doc);
    }

    private static String write(ObjectNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
