package com.xammer.cloud.xampulse.service.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.dto.XpRetention;
import com.xammer.cloud.xampulse.dto.XpSnippets;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Bucket policy and lifecycle generation for EXISTING mode, and the CREATE-mode parameters. */
class BucketAndRetentionPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::123456789012:role/XamOpsXamPulseArchiveRole";
    private static final XpRetention R = new XpRetention(14, 30, 90, 365);
    private final RetentionPolicyService retention = new RetentionPolicyService(new SetupPlanValidator());
    private final BucketSnippetService snippets = new BucketSnippetService(retention);

    private JsonNode statement(JsonNode doc, String sid) {
        for (JsonNode s : doc.get("Statement")) {
            if (sid.equals(s.get("Sid").asText())) {
                return s;
            }
        }
        fail("no statement " + sid);
        return null;
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void bucketPolicyParsesAndDeniesEveryoneButTheArchiveRoleAndRootOnThePrefix() throws Exception {
        JsonNode doc = JSON.readTree(snippets.bucketPolicy("my-logs", "123456789012", ROLE));
        assertEquals("2012-10-17", doc.get("Version").asText());

        JsonNode allow = statement(doc, "AllowXamPulseArchiveRole");
        assertEquals("Allow", allow.get("Effect").asText());
        assertEquals(ROLE, allow.get("Principal").get("AWS").asText());
        assertEquals("arn:aws:s3:::my-logs/xamops-xampulse/*", allow.get("Resource").asText());
        assertFalse(texts(allow.get("Action")).stream().anyMatch(a -> a.contains("Delete") || a.equals("s3:*")));

        JsonNode denyGet = statement(doc, "DenyArchiveReadToOthers");
        assertEquals("Deny", denyGet.get("Effect").asText());
        assertEquals("*", denyGet.get("Principal").asText());
        assertEquals("s3:GetObject", denyGet.get("Action").asText());
        assertEquals("arn:aws:s3:::my-logs/xamops-xampulse/*", denyGet.get("Resource").asText());
        assertEquals(List.of(ROLE, "arn:aws:iam::123456789012:root"),
                texts(denyGet.get("Condition").get("ArnNotEquals").get("aws:PrincipalArn")));

        JsonNode denyList = statement(doc, "DenyArchiveListToOthers");
        assertEquals("s3:ListBucket", denyList.get("Action").asText());
        assertEquals("arn:aws:s3:::my-logs", denyList.get("Resource").asText());
        assertEquals("xamops-xampulse/*", denyList.get("Condition").get("StringLike").get("s3:prefix").asText());
        assertEquals(2, denyList.get("Condition").get("ArnNotEquals").get("aws:PrincipalArn").size());
    }

    @Test
    void lifecycleIsPrefixScopedWithTheSizeFilterOnTransitionsOnly() throws Exception {
        JsonNode doc = JSON.readTree(retention.lifecycleJson(R));
        JsonNode tier = doc.get("Rules").get(0);
        JsonNode expiry = doc.get("Rules").get(1);

        assertEquals("xamops-xampulse/", tier.get("Filter").get("And").get("Prefix").asText());
        assertEquals(131072, tier.get("Filter").get("And").get("ObjectSizeGreaterThan").asLong());
        assertEquals(30, tier.get("Transitions").get(0).get("Days").asInt());
        assertEquals("STANDARD_IA", tier.get("Transitions").get(0).get("StorageClass").asText());
        assertEquals(90, tier.get("Transitions").get(1).get("Days").asInt());
        assertEquals("GLACIER_IR", tier.get("Transitions").get(1).get("StorageClass").asText());
        assertNull(tier.get("Expiration"));

        // Expiry must not carry the size filter, otherwise small objects would never expire.
        assertEquals("xamops-xampulse/", expiry.get("Filter").get("Prefix").asText());
        assertNull(expiry.get("Filter").get("And"));
        assertEquals(365, expiry.get("Expiration").get("Days").asInt());
        assertEquals(30, expiry.get("NoncurrentVersionExpiration").get("NoncurrentDays").asInt());
        assertEquals(7, expiry.get("AbortIncompleteMultipartUpload").get("DaysAfterInitiation").asInt());
    }

    @Test
    void lifecycleFollowsTheChosenRetention() throws Exception {
        JsonNode doc = JSON.readTree(retention.lifecycleJson(new XpRetention(7, 45, 120, 730)));
        assertEquals(45, doc.get("Rules").get(0).get("Transitions").get(0).get("Days").asInt());
        assertEquals(120, doc.get("Rules").get(0).get("Transitions").get(1).get("Days").asInt());
        assertEquals(730, doc.get("Rules").get(1).get("Expiration").get("Days").asInt());
    }

    @Test
    void createModeParametersCarryOnlyTheThreeS3Numbers() {
        assertEquals(Map.of("IaAfterDays", "30", "GlacierIrAfterDays", "90", "ExpireAfterDays", "365"), retention.createModeParameters(R));
    }

    @Test
    void invalidRetentionIsRefused() {
        assertThrows(XamPulseException.class, () -> retention.validateOrThrow(new XpRetention(14, 30, 90, 100)));
        retention.validateOrThrow(R);
    }

    @Test
    void kmsGrantAllowsOnlyDataKeyAndDecryptForTheArchiveRole() throws Exception {
        JsonNode doc = JSON.readTree(snippets.kmsGrant(ROLE));
        JsonNode s = doc.get("Statement").get(0);
        assertEquals(ROLE, s.get("Principal").get("AWS").asText());
        assertEquals(List.of("kms:GenerateDataKey", "kms:Decrypt"), texts(s.get("Action")));
    }

    @Test
    void existingBucketSnippetsIncludeKmsGrantOnlyWhenAKeyIsGiven() {
        XpSnippets withKey = snippets.forExistingBucket("my-logs", "123456789012", "", "arn:aws:kms:ap-south-1:123456789012:key/abc", R);
        assertNotNull(withKey.kmsGrant());
        assertNotNull(withKey.bucketPolicy());
        assertNotNull(withKey.lifecycleJson());
        assertNull(snippets.forExistingBucket("my-logs", "123456789012", "", null, R).kmsGrant());
        assertTrue(snippets.forExistingBucket("my-logs", "123456789012", "-uat", null, R).bucketPolicy().contains("XamOpsXamPulseArchiveRole-uat"));
    }

    @Test
    void archiveRoleArnMatchesTheTemplateRoleName() {
        assertEquals(ROLE, BucketSnippetService.archiveRoleArn("123456789012", ""));
    }
}
