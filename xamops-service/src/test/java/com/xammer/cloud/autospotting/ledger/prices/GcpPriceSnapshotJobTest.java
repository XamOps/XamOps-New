package com.xammer.cloud.autospotting.ledger.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.gcp.GcpMachineShapes;
import com.xammer.cloud.service.autospotting.gcp.GcpMigClient;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.prices.GcpPriceSnapshotJob;
import com.xammer.cloud.service.autospotting.ledger.prices.GcpShapeStore;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceKeyReader;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceSnapshotStore;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** plan-phase-30.md 30.1: Catalog core and RAM stored separately and exactly; a failed fetch writes nothing; PD-09 shapes. */
class GcpPriceSnapshotJobTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PriceKeyReader keys = mock(PriceKeyReader.class);
    private final PriceSnapshotStore store = mock(PriceSnapshotStore.class);
    private final GcpShapeStore shapeStore = mock(GcpShapeStore.class);
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final GcpMigClient.ReadOnly reads = mock(GcpMigClient.ReadOnly.class);
    private final Instant now = Instant.parse("2026-10-05T01:15:00Z");
    private final GcpPriceSnapshotJob job = new GcpPriceSnapshotJob(mock(JobRunner.class), mock(LedgerGate.class), keys,
            store, shapeStore, a -> new GcpReadOnlyView("xamops-test", reads), new GcpMachineShapes(), accounts,
            new AutoSpottingMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC));

    private static JsonNode fixture(String name) {
        try (InputStream in = GcpPriceSnapshotJobTest.class.getResourceAsStream("/autospotting/gcp/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }

    private void managed(String type, String machineTypeFixture) {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        when(accounts.findById(7L)).thenReturn(Optional.of(a));
        when(keys.readGcp()).thenReturn(List.of(new PriceKeyReader.GcpKey(7L, "us-central1", "us-central1-a", type)));
        when(reads.getMachineType("us-central1-a", type)).thenReturn(fixture(machineTypeFixture));
        List<JsonNode> skus = new ArrayList<>();
        fixture("listskus-compute.json").path("skus").forEach(skus::add);
        when(reads.listCatalogSkus()).thenReturn(skus);
        when(store.insert(anyString(), any())).thenReturn(true);
        when(shapeStore.insert(any(), any())).thenReturn(true);
    }

    @Test
    void storesCoreAndRamSeparately_forOnDemandAndSpot_atTheExactUnitPrice() {
        managed("n2-standard-4", "machinetype-n2-standard-4.json");
        job.snapshot();
        var cap = ArgumentCaptor.forClass(PriceSnapshotStore.NewSnapshot.class);
        verify(store, org.mockito.Mockito.times(4)).insert(org.mockito.ArgumentMatchers.eq("GCP"), cap.capture());
        var rows = cap.getAllValues();
        assertThat(rows).extracting(PriceSnapshotStore.NewSnapshot::skuOrType)
                .containsExactlyInAnyOrder("n2:CORE", "n2:RAM", "n2:CORE", "n2:RAM");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.source()).isEqualTo("GCP_CATALOG");
            assertThat(r.currency()).isEqualTo("USD");
            assertThat(r.region()).isEqualTo("us-central1");
            assertThat(r.zone()).isNull();
            assertThat(r.sourceEffectiveTimeStated()).as("the fixture states no effective time").isFalse();
            assertThat(r.effectiveFrom()).isEqualTo(now);
        });
        assertRate(rows, "ON_DEMAND", "n2:CORE", "VCPU_HOUR", "0.031611");
        assertRate(rows, "ON_DEMAND", "n2:RAM", "GIB_HOUR", "0.004237");
        assertRate(rows, "SPOT", "n2:CORE", "VCPU_HOUR", "0.00758");
        assertRate(rows, "SPOT", "n2:RAM", "GIB_HOUR", "0.001016");
    }

    private static void assertRate(List<PriceSnapshotStore.NewSnapshot> rows, String kind, String sku, String unit,
                                   String rate) {
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.priceKind()).isEqualTo(kind);
            assertThat(r.skuOrType()).isEqualTo(sku);
            assertThat(r.unit()).isEqualTo(unit);
            assertThat(r.rate()).isEqualByComparingTo(rate);
        });
    }

    @Test
    void sharedCoreShapeIsStoredFromTheVersionedTable_notGuestCpus() {
        managed("e2-small", "machinetype-e2-small.json");
        job.snapshot();
        var cap = ArgumentCaptor.forClass(GcpShapeStore.StoredShape.class);
        verify(shapeStore).insert(cap.capture(), any());
        assertThat(cap.getValue().billedVcpu()).isEqualByComparingTo("0.5");
        assertThat(cap.getValue().sharedCore()).isTrue();
        assertThat(cap.getValue().tableVersion()).isEqualTo("gcp-shared-core-v1");
        assertThat(cap.getValue().series()).isEqualTo("e2");
    }

    @Test
    void aSharedCoreTypeMissingFromTheTableIsStoredWithNoBilledVcpu() {
        managed("e2-future", "machinetype-e2-future-shared.json");
        job.snapshot();
        var cap = ArgumentCaptor.forClass(GcpShapeStore.StoredShape.class);
        verify(shapeStore).insert(cap.capture(), any());
        assertThat(cap.getValue().billedVcpu()).isNull();
    }

    @Test
    void aFailedCatalogFetchWritesNothing() {
        managed("n2-standard-4", "machinetype-n2-standard-4.json");
        when(reads.listCatalogSkus()).thenThrow(new GcpReadException("skus.list", ErrorKind.TRANSIENT));
        assertThat(job.snapshot()).isZero();
        verify(store, never()).insert(anyString(), any());
        verify(shapeStore, never()).insert(any(), any());
    }

    @Test
    void anUnreadableMachineTypeStoresNoShapeAndNoPrice() {
        managed("n2-standard-4", "machinetype-n2-standard-4.json");
        when(reads.getMachineType(anyString(), anyString())).thenThrow(new GcpReadException("machineTypes.get", ErrorKind.TRANSIENT));
        assertThat(job.snapshot()).isZero();
        verify(store, never()).insert(anyString(), any());
    }

    @Test
    void aSeriesWithNoMatchingSkusStoresNoPrice() {
        managed("n2-standard-4", "machinetype-n2-standard-4.json");
        when(reads.listCatalogSkus()).thenReturn(List.of());
        job.snapshot();
        verify(store, never()).insert(anyString(), any());
    }
}
