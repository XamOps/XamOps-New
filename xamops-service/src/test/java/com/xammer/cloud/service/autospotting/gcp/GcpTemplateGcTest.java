package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** AC-GCP-05: only registry rows are deleted, only retired, past grace, unreferenced, with a matching id. */
class GcpTemplateGcTest {
    private final GcpConversionFixture f = new GcpConversionFixture();
    private String spot;
    private UUID rowId;

    private GcpTemplateGc gc() {
        com.xammer.cloud.domain.CloudAccount account = f.accounts.findById(3L).orElseThrow();
        when(f.accounts.findByAwsAccountIdOrGcpProjectId(anyString(), anyString())).thenReturn(List.of(account));
        return new GcpTemplateGc(f.registry, f.accounts, a -> f.gcp.view(), a -> f.mutating, f.gate, f.clock, Duration.ofDays(7));
    }

    /** A registered, IN_USE Spot template that the MIG no longer references and that is RETIRED past its grace. */
    private void retiredCopy() {
        spot = FakeGcp.P + "/global/instanceTemplates/web-mig-xas-aaaaaaaa";
        GcpTemplateRegistry.Row r = f.registry.registerCreating(f.groupId, 1, "SPOT", "web-mig-xas-aaaaaaaa", "global", spot,
                GcpConversionFixture.ORIGINAL, "7700", "sha", f.opId, f.clock.instant());
        rowId = r.id();
        f.registry.markInUse(rowId, "9001", f.clock.instant());
        ObjectNode t = GcpConversionFixture.JSON.createObjectNode().put("id", "9001").put("name", "web-mig-xas-aaaaaaaa");
        f.gcp.templates.put(spot, t);
        f.registry.retireUnreferenced(f.groupId, List.of(GcpConversionFixture.ORIGINAL), Duration.ofDays(7), f.clock.instant());
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("RETIRED");
    }

    @Test
    void nothingIsDeletedBeforeTheGracePeriodEnds() {
        retiredCopy();
        f.clock.advance(Duration.ofDays(6));
        assertThat(gc().runOnce().deleted()).isZero();
        assertThat(f.mutating.deleted).isEmpty();
    }

    @Test
    void aRetiredRegistryTemplatePastGraceAndUnreferencedIsDeleted() {
        retiredCopy();
        f.clock.advance(Duration.ofDays(8));
        GcpTemplateGc.Result r = gc().runOnce();
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(f.mutating.deleted).containsExactly("web-mig-xas-aaaaaaaa");
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("DELETED");
    }

    @Test
    void theOriginalCustomerTemplateIsNeverDeleted_itIsNotInTheRegistry() {
        retiredCopy();
        f.clock.advance(Duration.ofDays(8));
        gc().runOnce();
        assertThat(f.mutating.deleted).doesNotContain("web-tpl-1");
        assertThat(f.registry.rows).noneMatch(r -> r.selfLink().equals(GcpConversionFixture.ORIGINAL));
    }

    @Test
    void aTemplateTheMigStillReferencesIsKeptAndGetsANewGracePeriod() {
        retiredCopy();
        ((ObjectNode) f.gcp.mig()).put("instanceTemplate", spot);
        f.clock.advance(Duration.ofDays(8));
        GcpTemplateGc.Result r = gc().runOnce();
        assertThat(r.keptInUse()).isEqualTo(1);
        assertThat(f.mutating.deleted).isEmpty();
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("RETIRED");
    }

    @Test
    void aCustomerTemplateRecreatedUnderTheSameNameHasADifferentIdAndIsNeverDeleted() {
        retiredCopy();
        ((ObjectNode) f.gcp.templates.get(spot)).put("id", "55555");
        f.clock.advance(Duration.ofDays(8));
        assertThat(gc().runOnce().gone()).isEqualTo(1);
        assertThat(f.mutating.deleted).isEmpty();
    }

    @Test
    void aTemplateNamedLikeOursButNotInTheRegistryIsNeverDeleted_whateverItsNameDescriptionOrLabel() {
        retiredCopy();
        for (String name : new String[] {"web-mig-spot", "web-tpl-od-1", "web-mig-xas-deadbeef", "web-tpl-1-spot"}) {
            String link = FakeGcp.P + "/global/instanceTemplates/" + name;
            ObjectNode t = GcpConversionFixture.JSON.createObjectNode().put("id", "42").put("name", name)
                    .put("description", "XamOps AutoSpotting copy of web-tpl-1 for group " + f.groupId);
            t.putObject("properties").putObject("labels").put("xamops-autospotting", f.groupId.toString());
            f.gcp.templates.put(link, t);
        }
        f.clock.advance(Duration.ofDays(60));
        GcpTemplateGc.Result r = gc().runOnce();
        assertThat(r.deleted()).as("only the registered, retired copy").isEqualTo(1);
        assertThat(f.mutating.deleted).containsExactly("web-mig-xas-aaaaaaaa");
    }
    @Test
    void anOrphanCreatingRowOfATerminalOpIsRetiredNeverDeletedInlineAndDeletedOnlyAfterTheGrace() {
        // An op that was preempted after its insert: the row stayed CREATING and the template exists.
        spot = FakeGcp.P + "/global/instanceTemplates/web-mig-xas-bbbbbbbb";
        UUID op = UUID.randomUUID();
        GcpTemplateRegistry.Row r = f.registry.registerCreating(f.groupId, 1, "SPOT", "web-mig-xas-bbbbbbbb", "global", spot,
                GcpConversionFixture.ORIGINAL, "7700", "sha", op, f.clock.instant());
        ObjectNode t = GcpConversionFixture.JSON.createObjectNode().put("id", "9100").put("name", "web-mig-xas-bbbbbbbb")
                .put("description", "XamOps AutoSpotting copy of web-tpl-1 for group " + f.groupId);
        t.putObject("properties").putObject("scheduling").put("provisioningModel", "SPOT");
        t.with("properties").putObject("labels").put("xamops-autospotting", f.groupId.toString());
        f.gcp.templates.put(spot, t);
        f.registry.terminalOps.add(op);
        assertThat(gc().runOnce().deleted()).isZero();
        assertThat(f.mutating.deleted).as("retired, never deleted inline").isEmpty();
        assertThat(f.registry.findByOp(op).orElseThrow().status()).isEqualTo("RETIRED");
        f.clock.advance(Duration.ofDays(8));
        assertThat(gc().runOnce().deleted()).isEqualTo(1);
        assertThat(f.mutating.deleted).containsExactly("web-mig-xas-bbbbbbbb");
    }
    @Test
    void anOrphanThatIsGoneFromTheCloudIsMarkedGoneAndOneThatIsNotOursIsLeftAlone() {
        UUID op = UUID.randomUUID();
        String gone = FakeGcp.P + "/global/instanceTemplates/web-mig-xas-cccccccc";
        f.registry.registerCreating(f.groupId, 1, "SPOT", "web-mig-xas-cccccccc", "global", gone,
                GcpConversionFixture.ORIGINAL, "7700", "sha", op, f.clock.instant());
        UUID op2 = UUID.randomUUID();
        String theirs = FakeGcp.P + "/global/instanceTemplates/web-mig-xas-dddddddd";
        f.registry.registerCreating(f.groupId, 2, "SPOT", "web-mig-xas-dddddddd", "global", theirs,
                GcpConversionFixture.ORIGINAL, "7700", "sha", op2, f.clock.instant());
        f.gcp.templates.put(theirs, GcpConversionFixture.JSON.createObjectNode().put("id", "5").put("name", "web-mig-xas-dddddddd"));
        f.registry.terminalOps.add(op);
        f.registry.terminalOps.add(op2);
        f.clock.advance(Duration.ofDays(30));
        assertThat(gc().runOnce().deleted()).isZero();
        assertThat(f.registry.findByOp(op).orElseThrow().status()).isEqualTo("GONE");
        assertThat(f.registry.findByOp(op2).orElseThrow().status()).isEqualTo("GONE");
        assertThat(f.mutating.deleted).isEmpty();
    }
    @Test
    void whileTheGateIsClosedGcDoesNothing() {
        retiredCopy();
        f.gateOpen = false;
        f.wire();
        f.clock.advance(Duration.ofDays(8));
        assertThat(gc().runOnce()).isEqualTo(new GcpTemplateGc.Result(0, 0, 0, 0));
        assertThat(f.mutating.deleted).isEmpty();
    }
}
