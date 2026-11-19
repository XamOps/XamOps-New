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
    void whileTheGateIsClosedGcDoesNothing() {
        retiredCopy();
        f.gateOpen = false;
        f.wire();
        f.clock.advance(Duration.ofDays(8));
        assertThat(gc().runOnce()).isEqualTo(new GcpTemplateGc.Result(0, 0, 0, 0));
        assertThat(f.mutating.deleted).isEmpty();
    }
}
