package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcpFingerprintTest {
    private static GcpMigFacts facts(ObjectNode mig) {
        return GcpMigFacts.from(FakeGcp.PROJECT, mig);
    }

    @Test
    void theOwnedFingerprintCoversTemplateVersionsAndFlexibilityOnly() {
        ObjectNode base = FakeGcp.obj("mig-regional-web.json");
        String fp = GcpFingerprint.of(facts(base));
        // Outside the fingerprint: targetSize, updatePolicy, status, autohealing, the raw etag.
        ObjectNode other = base.deepCopy();
        other.put("targetSize", 40);
        other.putObject("updatePolicy").put("type", "PROACTIVE");
        other.put("fingerprint", "bmV3");
        other.putObject("status").put("isStable", false);
        assertThat(GcpFingerprint.of(facts(other))).isEqualTo(fp);
        // Inside it: the top-level template, the versions, the flexibility policy.
        ObjectNode template = base.deepCopy();
        template.put("instanceTemplate", FakeGcp.P + "/global/instanceTemplates/web-tpl-2");
        assertThat(GcpFingerprint.of(facts(template))).isNotEqualTo(fp);
        ObjectNode versions = base.deepCopy();
        ((ObjectNode) versions.path("versions").get(0)).put("name", "xas-standard");
        assertThat(GcpFingerprint.of(facts(versions))).isNotEqualTo(fp);
        ObjectNode flex = base.deepCopy();
        flex.putObject("instanceFlexibilityPolicy").putObject("instanceSelections");
        assertThat(GcpFingerprint.of(facts(flex))).isNotEqualTo(fp);
    }

    @Test
    void versionsAreSortedByNameSoOrderIsNotDrift() {
        ObjectNode a = FakeGcp.obj("mig-regional-web.json");
        a.putArray("versions").addObject().put("name", "xas-spot").put("instanceTemplate", "t2");
        a.withArray("versions").addObject().put("name", "xas-standard").put("instanceTemplate", "t1");
        ObjectNode b = FakeGcp.obj("mig-regional-web.json");
        b.putArray("versions").addObject().put("name", "xas-standard").put("instanceTemplate", "t1");
        b.withArray("versions").addObject().put("name", "xas-spot").put("instanceTemplate", "t2");
        assertThat(GcpFingerprint.of(facts(a))).isEqualTo(GcpFingerprint.of(facts(b)));
    }

    @Test
    void thePlanHashAddsUpdatePolicyAutohealingAndAllInstancesConfigButNotSizeOrStatus() {
        ObjectNode base = FakeGcp.obj("mig-regional-web.json");
        String hash = GcpFingerprint.planHash(facts(base));
        ObjectNode resized = base.deepCopy();
        resized.put("targetSize", 12);
        resized.put("fingerprint", "bmV3");
        resized.putObject("status").put("isStable", false);
        assertThat(GcpFingerprint.planHash(facts(resized))).as("an autoscaler resize is not staleness").isEqualTo(hash);
        ObjectNode policy = base.deepCopy();
        ((ObjectNode) policy.path("updatePolicy")).put("type", "PROACTIVE");
        assertThat(GcpFingerprint.planHash(facts(policy))).isNotEqualTo(hash);
        ObjectNode heal = base.deepCopy();
        heal.putArray("autoHealingPolicies").addObject().put("healthCheck", "hc");
        assertThat(GcpFingerprint.planHash(facts(heal))).isNotEqualTo(hash);
        ObjectNode all = base.deepCopy();
        all.putObject("allInstancesConfig").putObject("properties").putObject("labels").put("a", "b");
        assertThat(GcpFingerprint.planHash(facts(all))).isNotEqualTo(hash);
        assertThat(GcpFingerprint.of(facts(all))).as("allInstancesConfig is restored, not owned").isEqualTo(GcpFingerprint.of(facts(base)));
    }
}
