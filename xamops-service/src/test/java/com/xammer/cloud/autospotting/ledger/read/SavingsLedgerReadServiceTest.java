package com.xammer.cloud.autospotting.ledger.read;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.ledger.read.JdbcSavingsLedgerReadService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** design-ledger.md §10.2: periods must be UTC month boundaries; anything else throws before any query. */
class SavingsLedgerReadServiceTest {
    private final DataSource ds = mock(DataSource.class);
    private final JdbcSavingsLedgerReadService service = new JdbcSavingsLedgerReadService(ds);

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(1L);
        a.setProvider("AWS");
        return a;
    }

    @Test
    void midMonthStartIsRejected() {
        assertThatThrownBy(() -> service.getSavingsSummary(account(), Instant.parse("2026-10-02T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ds);
    }

    @Test
    void notMidnightUtcIsRejected() {
        assertThatThrownBy(() -> service.getSavingsSummary(account(), Instant.parse("2026-10-01T05:30:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endNotAfterStartIsRejected() {
        assertThatThrownBy(() -> service.getSavingsSummary(account(), Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
    }
}
