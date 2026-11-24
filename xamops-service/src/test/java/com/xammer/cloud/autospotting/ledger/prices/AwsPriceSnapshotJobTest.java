package com.xammer.cloud.autospotting.ledger.prices;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.prices.*;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** plan-phase-17.md 17.1, AC-LED-10: a failed fetch writes nothing; prices are exact decimals. */
class AwsPriceSnapshotJobTest {
    private final PriceKeyReader keys = mock(PriceKeyReader.class);
    private final PriceSnapshotStore store = mock(PriceSnapshotStore.class);
    private final AwsPriceSource source = mock(AwsPriceSource.class);
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final Instant now = Instant.parse("2026-10-05T10:05:00Z");
    private final AwsPriceSnapshotJob job = new AwsPriceSnapshotJob(mock(JobRunner.class), mock(LedgerGate.class), keys,
            store, source, accounts, new AutoSpottingMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC));

    private void oneKey() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        when(accounts.findById(7L)).thenReturn(Optional.of(a));
        when(keys.read()).thenReturn(List.of(new PriceKeyReader.Key(7L, "us-east-1", "us-east-1a", "m5.xlarge", "LINUX")));
        when(store.latestSpotFrom(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void failedSpotFetchWritesNothing() {
        oneKey();
        when(source.spotHistory(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("throttled"));
        assertThat(job.snapshotSpot()).isZero();
        verify(store, never()).insert(any());
    }

    @Test
    void failedListFetchWritesNothing() {
        oneKey();
        when(source.listPrice(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));
        assertThat(job.snapshotList()).isZero();
        verify(store, never()).insert(any());
    }

    @Test
    void spotRowsKeepTheExactPriceTimestampAndAccountQualifiedZone_andZeroIsSkipped() {
        oneKey();
        Instant t = Instant.parse("2026-10-05T09:00:00Z");
        when(source.spotHistory(any(), any(), any(), any(), any())).thenReturn(List.of(
                new AwsPriceSource.SpotQuote("us-east-1a", "m5.xlarge", "Linux/UNIX", new BigDecimal("0.0570000000"), t),
                new AwsPriceSource.SpotQuote("us-east-1a", "m5.xlarge", "Linux/UNIX", BigDecimal.ZERO, t.plusSeconds(60)),
                new AwsPriceSource.SpotQuote("us-east-1b", "m5.xlarge", "Linux/UNIX", new BigDecimal("0.06"), t)));
        when(store.insert(any())).thenReturn(true);
        assertThat(job.snapshotSpot()).isEqualTo(1);
        var cap = org.mockito.ArgumentCaptor.forClass(PriceSnapshotStore.NewSnapshot.class);
        verify(store).insert(cap.capture());
        var s = cap.getValue();
        assertThat(s.rate()).isEqualByComparingTo("0.0570000000");
        assertThat(s.rate().toPlainString()).isEqualTo("0.0570000000");
        assertThat(s.zone()).isEqualTo("7/us-east-1a");
        assertThat(s.effectiveFrom()).isEqualTo(t);
        assertThat(s.sourceEffectiveTimeStated()).isTrue();
        assertThat(s.currency()).isEqualTo("USD");
    }

    @Test
    void listPriceStampsOfferVersionAndTermEffectiveDate() {
        oneKey();
        Instant eff = Instant.parse("2026-09-01T00:00:00Z");
        when(source.listPrice(eq("us-east-1"), eq("m5.xlarge"), any(), any(), any())).thenReturn(Optional.of(
                new AwsPriceSource.ListPrice(new BigDecimal("0.1920000000"), eff, "2026-09-30|2026-09-30T00:00:00Z", "{}")));
        when(store.insert(any())).thenReturn(true);
        assertThat(job.snapshotList()).isEqualTo(1);
        var cap = org.mockito.ArgumentCaptor.forClass(PriceSnapshotStore.NewSnapshot.class);
        verify(store).insert(cap.capture());
        assertThat(cap.getValue().effectiveFrom()).isEqualTo(eff);
        assertThat(cap.getValue().sourceVersion()).isEqualTo("2026-09-30|2026-09-30T00:00:00Z");
        assertThat(cap.getValue().priceKind()).isEqualTo("ON_DEMAND");
    }

    @Test
    void noManagedKeysMeansNoFetch() {
        when(keys.read()).thenReturn(List.of());
        assertThat(job.snapshotSpot()).isZero();
        assertThat(job.snapshotList()).isZero();
        verifyNoInteractions(source);
    }
}
