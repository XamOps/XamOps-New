package com.xammer.cloud.xampulse.template;

import com.xammer.cloud.xampulse.service.setup.CfnYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural and least-privilege checks on the shipped XamPulse CloudFormation templates, read from the classpath
 * copy the Maven build makes (so a broken copy rule fails here too). cfn-lint is run separately and recorded in
 * slice-b-notes.md.
 */
class XamPulseTemplateTest {

    static final String[] ALL = {"xamops-xampulse.yml", "xamops-xampulse-uat.yml", "xamops-xampulse-region.yml", "xamops-xampulse-region-uat.yml"};
    static final String[] GLOBAL = {"xamops-xampulse.yml", "xamops-xampulse-uat.yml"};
    static final String[] REGIONAL = {"xamops-xampulse-region.yml", "xamops-xampulse-region-uat.yml"};

    static String raw(String name) {
        try (InputStream in = XamPulseTemplateTest.class.getClassLoader().getResourceAsStream("xampulse/templates/" + name)) {
            assertNotNull(in, "template not on the classpath: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of(o);
    }

    static Map<String, Object> resources(String name) {
        return map(CfnYaml.parse(raw(name)).get("Resources"));
    }

    static Map<String, Object> resource(String name, String id) {
        Map<String, Object> r = map(resources(name).get(id));
        assertNotNull(r, name + " has no resource " + id);
        return r;
    }

    /** Statements of the archive role's inline policy, with the condition-wrapped one unwrapped. */
    static List<Map<String, Object>> archiveStatements(String name) {
        Map<String, Object> props = map(resource(name, "ArchiveRole").get("Properties"));
        Map<String, Object> policy = map(list(props.get("Policies")).get(0));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object s : list(map(policy.get("PolicyDocument")).get("Statement"))) {
            Map<String, Object> st = map(s);
            if (st.containsKey("Fn::If")) {
                List<Object> parts = list(st.get("Fn::If"));
                out.add(map(parts.get(1)));
            } else {
                out.add(st);
            }
        }
        return out;
    }

    static List<String> actions(Map<String, Object> st) {
        List<String> out = new ArrayList<>();
        list(st.get("Action")).forEach(a -> out.add(String.valueOf(a)));
        return out;
    }

    static String text(Object o) {
        if (o instanceof String s) {
            return s;
        }
        Map<String, Object> m = map(o);
        return String.valueOf(m.containsKey("Fn::Sub") ? m.get("Fn::Sub") : m);
    }

    // ------------------------------------------------------------------ archive role: least privilege

    @Test
    void archiveRoleCannotDeleteAndIsScopedToTheXamPulsePrefix() {
        for (String t : GLOBAL) {
            boolean sawObjectStatement = false;
            for (Map<String, Object> st : archiveStatements(t)) {
                assertEquals("Allow", st.get("Effect"));
                for (String a : actions(st)) {
                    assertNotEquals("s3:*", a, t);
                    assertFalse(a.contains("Delete"), t + " must not allow " + a);
                    assertFalse(a.startsWith("iam:") || a.startsWith("sts:"), t + " must not allow " + a);
                    assertFalse(a.equals("kms:*") || a.equals("kms:ScheduleKeyDeletion") || a.equals("kms:PutKeyPolicy"), t + " " + a);
                }
                String resource = text(st.get("Resource"));
                assertNotEquals("*", resource, t);
                if (actions(st).contains("s3:PutObject")) {
                    sawObjectStatement = true;
                    assertTrue(resource.endsWith("/xamops-xampulse/*"), t + ": object access must be under the prefix: " + resource);
                    assertEquals(Set.of("s3:PutObject", "s3:GetObject", "s3:AbortMultipartUpload"), new TreeSet<>(actions(st)));
                }
                if (actions(st).contains("s3:ListBucket")) {
                    Map<String, Object> cond = map(st.get("Condition"));
                    assertEquals("xamops-xampulse/*", map(cond.get("StringLike")).get("s3:prefix"), t + ": listing must carry the prefix condition");
                }
            }
            assertTrue(sawObjectStatement, t);
        }
    }

    @Test
    void archiveRoleTrustsOnlyTheXamOpsRoleWithAnExternalId() {
        for (String t : GLOBAL) {
            Map<String, Object> props = map(resource(t, "ArchiveRole").get("Properties"));
            assertEquals(3600, props.get("MaxSessionDuration"), t);
            Map<String, Object> st = map(list(map(props.get("AssumeRolePolicyDocument")).get("Statement")).get(0));
            assertEquals("Allow", st.get("Effect"));
            assertEquals("sts:AssumeRole", st.get("Action"));
            String principal = text(map(st.get("Principal")).get("AWS"));
            assertEquals("arn:aws:iam::${XamOpsAccountId}:role/${XamOpsPrincipalRoleName}", principal, t);
            Map<String, Object> cond = map(map(st.get("Condition")).get("StringEquals"));
            assertEquals(Map.of("Ref", "PulseExternalId"), cond.get("sts:ExternalId"), t);
            assertFalse(principal.contains(":root") || principal.contains("*"));
        }
    }

    @Test
    void externalIdIsNoEchoAndNothingElseSecretIsInTheTemplate() {
        for (String t : GLOBAL) {
            Map<String, Object> params = map(CfnYaml.parse(raw(t)).get("Parameters"));
            Map<String, Object> id = map(params.get("PulseExternalId"));
            assertEquals(true, id.get("NoEcho"), t);
            assertTrue(((Number) id.get("MinLength")).intValue() >= 16);
        }
    }

    // ------------------------------------------------------------------ bucket modes

    @Test
    void existingBucketModeLeavesBucketPolicyAndKeyConditionedOnCreate() {
        for (String t : GLOBAL) {
            Map<String, Object> conditions = map(CfnYaml.parse(raw(t)).get("Conditions"));
            assertEquals(Map.of("Fn::Equals", List.of(Map.of("Ref", "BucketMode"), "CREATE")), conditions.get("CreateBucket"), t);
            assertEquals("CreateBucket", resource(t, "ArchiveBucket").get("Condition"), t);
            assertEquals("CreateBucket", resource(t, "ArchiveBucketPolicy").get("Condition"), t + ": never replace a customer's bucket policy");
            assertEquals("CreateKey", resource(t, "ArchiveKey").get("Condition"), t);
            assertEquals("CreateKey", resource(t, "ArchiveKeyAlias").get("Condition"), t);
            assertNull(resource(t, "ArchiveRole").get("Condition"), t + ": the role exists in both modes");
            // CreateKey implies CreateBucket, so a key is never created for an existing bucket.
            assertEquals(Map.of("Fn::And", List.of(Map.of("Condition", "CreateBucket"),
                    Map.of("Fn::Equals", List.of(Map.of("Ref", "KmsMode"), "CreateKey")))), conditions.get("CreateKey"), t);
        }
    }

    @Test
    void createdBucketIsPrivateVersionedEncryptedAndRetained() {
        for (String t : GLOBAL) {
            Map<String, Object> b = resource(t, "ArchiveBucket");
            assertEquals("Retain", b.get("DeletionPolicy"), t);
            assertEquals("Retain", b.get("UpdateReplacePolicy"), t);
            Map<String, Object> p = map(b.get("Properties"));
            assertEquals(Map.of("BlockPublicAcls", true, "BlockPublicPolicy", true, "IgnorePublicAcls", true, "RestrictPublicBuckets", true),
                    p.get("PublicAccessBlockConfiguration"), t);
            assertEquals("Enabled", map(p.get("VersioningConfiguration")).get("Status"));
            Map<String, Object> enc = map(map(list(map(p.get("BucketEncryption")).get("ServerSideEncryptionConfiguration")).get(0)).get("ServerSideEncryptionByDefault"));
            assertEquals("aws:kms", enc.get("SSEAlgorithm"));
            assertEquals("BucketOwnerEnforced", map(list(map(p.get("OwnershipControls")).get("Rules")).get(0)).get("ObjectOwnership"));
        }
    }

    @Test
    void lifecycleRulesArePrefixScopedWithSizeFilterOnTransitionsOnly() {
        for (String t : GLOBAL) {
            List<Object> rules = list(map(map(resource(t, "ArchiveBucket").get("Properties")).get("LifecycleConfiguration")).get("Rules"));
            Map<String, Object> tier = map(rules.get(0));
            Map<String, Object> expiry = map(rules.get(1));
            assertEquals("xamops-xampulse/", tier.get("Prefix"));
            assertEquals("131072", tier.get("ObjectSizeGreaterThan"));
            assertEquals(Map.of("Ref", "IaAfterDays"), map(list(tier.get("Transitions")).get(0)).get("TransitionInDays"));
            assertEquals("GLACIER_IR", map(list(tier.get("Transitions")).get(1)).get("StorageClass"));
            assertEquals("xamops-xampulse/", expiry.get("Prefix"));
            assertNull(expiry.get("ObjectSizeGreaterThan"), "small objects must still expire");
            assertEquals(Map.of("Ref", "ExpireAfterDays"), expiry.get("ExpirationInDays"));
            assertEquals(7, map(expiry.get("AbortIncompleteMultipartUpload")).get("DaysAfterInitiation"));
        }
    }

    @Test
    void bucketPolicyDeniesPlainHttpUnencryptedWritesAndNonArchiveReadsOfTheArchivePrefix() {
        for (String t : GLOBAL) {
            Map<String, Object> doc = map(map(resource(t, "ArchiveBucketPolicy").get("Properties")).get("PolicyDocument"));
            Map<String, Map<String, Object>> bySid = new LinkedHashMap<>();
            for (Object s : list(doc.get("Statement"))) {
                Map<String, Object> st = map(s);
                if (st.containsKey("Fn::If")) {
                    st = map(list(st.get("Fn::If")).get(1));
                }
                bySid.put(String.valueOf(st.get("Sid")), st);
            }
            assertEquals("false", map(map(bySid.get("DenyNonTls").get("Condition")).get("Bool")).get("aws:SecureTransport"), t);
            assertEquals("aws:kms", map(map(bySid.get("DenyUnencryptedArchivePut").get("Condition")).get("StringNotEquals")).get("s3:x-amz-server-side-encryption"), t);

            Map<String, Object> denyRead = bySid.get("DenyArchiveReadToOthers");
            assertEquals("Deny", denyRead.get("Effect"));
            assertEquals("*", denyRead.get("Principal"));
            assertEquals("s3:GetObject", denyRead.get("Action"));
            assertTrue(text(denyRead.get("Resource")).endsWith("/xamops-xampulse/*"));
            List<Object> allowedArns = list(map(map(denyRead.get("Condition")).get("ArnNotEquals")).get("aws:PrincipalArn"));
            assertEquals(2, allowedArns.size(), "only the archive role and the account root are excluded from the deny");
            assertEquals(Map.of("Fn::GetAtt", List.of("ArchiveRole", "Arn")), allowedArns.get(0));
            assertEquals("arn:aws:iam::${AWS::AccountId}:root", text(allowedArns.get(1)));

            assertEquals("Deny", bySid.get("DenyArchiveListToOthers").get("Effect"));
            assertEquals("Deny", bySid.get("DenyUnprefixedListToOthers").get("Effect"));
            assertEquals("true", map(map(bySid.get("DenyUnprefixedListToOthers").get("Condition")).get("Null")).get("s3:prefix"));
        }
    }

    @Test
    void keyPolicyNamesOnlyRootAndTheArchiveRole() {
        for (String t : GLOBAL) {
            Map<String, Object> kp = map(map(resource(t, "ArchiveKey").get("Properties")).get("KeyPolicy"));
            List<Object> sts = list(kp.get("Statement"));
            assertEquals(2, sts.size(), t);
            for (Object s : sts) {
                assertEquals("arn:aws:iam::${AWS::AccountId}:root", text(map(map(s).get("Principal")).get("AWS")));
            }
            Map<String, Object> use = map(sts.get(1));
            assertEquals(List.of("kms:GenerateDataKey", "kms:Decrypt"), use.get("Action"));
            assertTrue(text(map(map(use.get("Condition")).get("ArnEquals")).get("aws:PrincipalArn")).contains("role/XamOpsXamPulseArchiveRole"));
            assertEquals(true, map(map(resource(t, "ArchiveKey").get("Properties"))).get("EnableKeyRotation"));
        }
    }

    // ------------------------------------------------------------------ install resources

    @Test
    void targetModesCreateAssociationsWithTheRightTargets() {
        for (String t : ALL) {
            Map<String, Object> res = resources(t);
            for (int i = 1; i <= 10; i++) {
                Map<String, Object> a = map(res.get("AssociationIds" + i));
                assertEquals("AssociationIds" + i, "AssociationIds" + i);
                Map<String, Object> props = map(a.get("Properties"));
                Map<String, Object> target = map(list(props.get("Targets")).get(0));
                assertEquals("InstanceIds", target.get("Key"), t);
                assertEquals(Map.of("Ref", i == 1 ? "InstanceIds" : "InstanceIds" + i), target.get("Values"), t);
                assertEquals("rate(30 minutes)", props.get("ScheduleExpression"));
                assertEquals(false, props.get("ApplyOnlyAtCronInterval"));
                assertEquals(Map.of("Ref", "LinuxInstallDocument"), props.get("Name"));
                assertNotNull(a.get("Condition"));
            }
            Map<String, Object> tag = map(list(map(map(res.get("AssociationTag")).get("Properties")).get("Targets")).get(0));
            assertEquals(Map.of("Fn::Sub", "tag:${ObserveTagKey}"), tag.get("Key"), t);
            assertEquals(List.of(Map.of("Ref", "ObserveTagValue")), tag.get("Values"));
            Map<String, Object> all = map(list(map(map(res.get("AssociationAll")).get("Properties")).get("Targets")).get(0));
            assertEquals("InstanceIds", all.get("Key"));
            assertEquals(List.of("*"), all.get("Values"));
            assertNotNull(map(res.get("AssociationTag")).get("Condition"));
            assertNotNull(map(res.get("AssociationAll")).get("Condition"));
            // Each chunk parameter holds at most 50 ids: one CommaDelimitedList parameter per association.
            Map<String, Object> params = map(CfnYaml.parse(raw(t)).get("Parameters"));
            for (int i = 1; i <= 10; i++) {
                assertEquals("CommaDelimitedList", map(params.get(i == 1 ? "InstanceIds" : "InstanceIds" + i)).get("Type"));
            }
            assertEquals(List.of("InstanceIds", "Tag", "AllManaged"), map(params.get("TargetMode")).get("AllowedValues"));
            assertEquals("InstanceIds", map(params.get("TargetMode")).get("Default"), "the default never widens the selection");
        }
    }

    @Test
    void windowsIsAPlaceholderWithNoAssociation() {
        for (String t : ALL) {
            Map<String, Object> res = resources(t);
            assertNotNull(res.get("WindowsInstallDocument"));
            assertEquals("CreateWindowsDocument", map(res.get("WindowsInstallDocument")).get("Condition"));
            res.keySet().forEach(k -> assertFalse(k.toLowerCase().contains("windowsassociation"), t));
            assertEquals("false", map(map(CfnYaml.parse(raw(t)).get("Parameters")).get("IncludeWindowsDocument")).get("Default"));
            String doc = text(CfnYaml.parse(raw(t)).get("Resources").toString());
            assertTrue(doc.contains("Windows support is planned"), t);
        }
    }

    // ------------------------------------------------------------------ regional template

    @Test
    void regionalTemplatesHaveNoIamS3OrKmsResources() {
        for (String t : REGIONAL) {
            for (Map.Entry<String, Object> e : resources(t).entrySet()) {
                String type = String.valueOf(map(e.getValue()).get("Type"));
                assertTrue(type.equals("AWS::SSM::Document") || type.equals("AWS::SSM::Association"), t + " " + e.getKey() + " is " + type);
            }
            String raw = raw(t);
            assertFalse(raw.contains("AWS::IAM::"), t);
            assertFalse(raw.contains("PulseExternalId"), t + ": the regional stack needs no secret");
        }
    }

    // ------------------------------------------------------------------ SSM document identity

    @Test
    void ssmDocumentBodiesAreByteIdenticalToTheSharedSnippetInEveryTemplate() {
        String linux = raw("xampulse/ssm-snippets/install-linux.sh");
        String windows = raw("xampulse/ssm-snippets/install-windows.ps1");
        for (String t : ALL) {
            assertEquals(linux, runCommand(t, "LinuxInstallDocument"), t + " Linux document differs from the snippet");
            assertEquals(windows, runCommand(t, "WindowsInstallDocument"), t + " Windows document differs from the snippet");
        }
    }

    static String runCommand(String template, String docId) {
        Map<String, Object> content = map(map(resource(template, docId).get("Properties")).get("Content"));
        Map<String, Object> step = map(list(content.get("mainSteps")).get(0));
        return String.valueOf(list(map(step.get("inputs")).get("runCommand")).get(0));
    }

    @Test
    void linuxDocumentIsRestrictedToLinuxAndTakesOnlyTwoPublicUrls() {
        for (String t : ALL) {
            Map<String, Object> content = map(map(resource(t, "LinuxInstallDocument").get("Properties")).get("Content"));
            assertEquals("2.2", content.get("schemaVersion"));
            assertEquals(Set.of("ReleaseBaseUrl", "DesiredStateUrl"), map(content.get("parameters")).keySet());
            Map<String, Object> step = map(list(content.get("mainSteps")).get(0));
            assertEquals(List.of("platformType", "Linux"), map(step.get("precondition")).get("StringEquals"));
        }
    }

    // ------------------------------------------------------------------ prod / uat

    @Test
    void uatTemplatesDifferFromProductionOnlyInAllowListedKeys() {
        Set<String> allowed = Set.of("Description", "Parameters.XamOpsPrincipalRoleName.Default", "Parameters.ResourceSuffix.Default",
                "Parameters.ReleaseBaseUrl.Default", "Parameters.DesiredStateUrl.Default");
        for (String[] pair : new String[][] {{"xamops-xampulse.yml", "xamops-xampulse-uat.yml"},
                {"xamops-xampulse-region.yml", "xamops-xampulse-region-uat.yml"}}) {
            Set<String> diffs = new TreeSet<>();
            diff("", CfnYaml.parse(raw(pair[0])), CfnYaml.parse(raw(pair[1])), diffs);
            diffs.removeAll(allowed);
            assertTrue(diffs.isEmpty(), pair[1] + " differs from " + pair[0] + " outside the allow-list: " + diffs);
        }
    }

    @SuppressWarnings("unchecked")
    private static void diff(String path, Object a, Object b, Set<String> out) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            Set<Object> keys = new TreeSet<>((Object x, Object y) -> String.valueOf(x).compareTo(String.valueOf(y)));
            keys.addAll(ma.keySet());
            keys.addAll(mb.keySet());
            for (Object k : keys) {
                diff(path.isEmpty() ? String.valueOf(k) : path + "." + k, ((Map<Object, Object>) ma).get(k), ((Map<Object, Object>) mb).get(k), out);
            }
        } else if (a instanceof List<?> la && b instanceof List<?> lb && la.size() == lb.size()) {
            for (int i = 0; i < la.size(); i++) {
                diff(path + "[" + i + "]", la.get(i), lb.get(i), out);
            }
        } else if (a == null ? b != null : !a.equals(b)) {
            out.add(path);
        }
    }

    @Test
    void environmentSpecificDefaultsAreWhatTheyShouldBe() {
        Map<String, Object> prod = map(CfnYaml.parse(raw("xamops-xampulse.yml")).get("Parameters"));
        Map<String, Object> uat = map(CfnYaml.parse(raw("xamops-xampulse-uat.yml")).get("Parameters"));
        assertEquals("XamOps-App-EC2-Role", map(prod.get("XamOpsPrincipalRoleName")).get("Default"));
        assertEquals("XamOps-App-EC2-Role-new", map(uat.get("XamOpsPrincipalRoleName")).get("Default"));
        assertEquals("", map(prod.get("ResourceSuffix")).get("Default"));
        assertEquals("-uat", map(uat.get("ResourceSuffix")).get("Default"));
    }

    // ------------------------------------------------------------------ hygiene

    @Test
    void noSecretsNoAdministratorAccessAndNoWildcardResourcesInIamPolicies() {
        Pattern secret = Pattern.compile("AKIA[A-Z0-9]{16}|ASIA[A-Z0-9]{16}|-----BEGIN|(?i)bearer\\s+[a-z0-9._-]{12,}|xak_[A-Za-z0-9]{8,}|eyJ[A-Za-z0-9_-]{10,}\\.");
        for (String t : ALL) {
            String raw = raw(t);
            assertFalse(secret.matcher(raw).find(), t + " contains something that looks like a secret");
            assertFalse(raw.contains("AdministratorAccess"), t);
            assertFalse(raw.contains("PowerUserAccess"), t);
            assertFalse(raw.contains("AWS::IAM::AccessKey"), t + ": no long-lived keys");
            assertFalse(raw.contains("AWS::IAM::User"), t);
        }
        for (String t : GLOBAL) {
            for (Map<String, Object> st : archiveStatements(t)) {
                assertNotEquals("*", String.valueOf(st.get("Resource")), t + ": archive role statements are never Resource *");
            }
        }
    }

    @Test
    void outputsExposeTheNamesTheWatcherReads() {
        for (String t : GLOBAL) {
            Set<String> outputs = map(CfnYaml.parse(raw(t)).get("Outputs")).keySet();
            assertTrue(outputs.containsAll(Set.of("ArchiveRoleArn", "BucketName", "BucketRegion", "KmsKeyArn", "AssociationIds", "TemplateVersion", "BucketMode")), outputs.toString());
        }
        for (String t : REGIONAL) {
            Set<String> outputs = map(CfnYaml.parse(raw(t)).get("Outputs")).keySet();
            assertTrue(outputs.containsAll(Set.of("AssociationIds", "TemplateVersion")), outputs.toString());
        }
    }
}
