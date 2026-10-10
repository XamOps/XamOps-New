package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The legacy filter of every savings query (D-12, AC-LED-11), run against a real schema.
 *
 * <p>The service tests mock this repository, so they prove which query a reader calls, not what
 * the query returns. This runs each query on H2, built from the entity: rows inserted the way the
 * legacy writers insert them get {@code legacy = true} from the column default and stay out of
 * every realized query. A non-legacy row can't exist in production ({@code ck_dss_legacy}), so
 * the one used here is a test-only update of a stored row.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class DailySpotSavingsRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Autowired
    private DailySpotSavingsRepository repository;

    @Autowired
    private EntityManager entityManager;

    private CloudAccount accountA;
    private CloudAccount accountB;

    @BeforeEach
    void setUp() {
        Client client = new Client();
        client.setName("legacy-savings-client");
        entityManager.persist(client);
        accountA = account(client, "account-a");
        accountB = account(client, "account-b");
    }

    private CloudAccount account(Client client, String name) {
        CloudAccount account = new CloudAccount();
        account.setAccountName(name);
        account.setProvider("AWS");
        account.setClient(client);
        entityManager.persist(account);
        return account;
    }

    /** Inserted as the legacy writers do: legacy, billable and source are left to the defaults. */
    private DailySpotSavings row(CloudAccount account, LocalDate date, double amount, int swaps) {
        DailySpotSavings row = new DailySpotSavings(account, date);
        row.setAmountSaved(amount);
        row.setSwapCount(swaps);
        return repository.saveAndFlush(row);
    }

    /** Test-only: H2 has no ck_dss_legacy, so a stored row can be turned non-legacy here. */
    private void markNonLegacy(DailySpotSavings row) {
        entityManager.createNativeQuery("UPDATE daily_spot_savings SET legacy = false WHERE id = :id")
                .setParameter("id", row.getId())
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void rowsWrittenTheLegacyWay_areLegacy_andNoRealizedQueryReturnsThem() {
        row(accountA, DAY, 10.0, 2);
        row(accountA, DAY.plusDays(1), 5.5, 1);
        row(accountB, DAY, 7.0, 3);
        entityManager.clear();

        assertThat(repository.findAll()).hasSize(3).allSatisfy(r -> {
            assertThat(r.getLegacy()).isTrue();
            assertThat(r.getBillable()).isFalse();
            assertThat(r.getSource()).isEqualTo("LEGACY");
        });

        // Realized reads: nothing.
        assertThat(repository.getTotalSavings()).isNull();
        assertThat(repository.getAggregatedHistory(DAY.minusDays(1))).isEmpty();
        assertThat(repository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(
                accountA.getId(), DAY, DAY.plusDays(1))).isEmpty();

        // Legacy reads: everything, per account and range.
        assertThat(repository.getLegacyTotalSavings()).isEqualTo(22.5);
        assertThat(repository.sumLegacyAmount(List.of(accountA.getId()), DAY, DAY.plusDays(1))).isEqualTo(15.5);
        assertThat(repository.sumLegacyAmount(List.of(accountA.getId(), accountB.getId()), DAY, DAY)).isEqualTo(17.0);
        assertThat(repository.sumLegacyAmount(List.of(accountA.getId()), DAY.plusDays(2), DAY.plusDays(9))).isNull();

        List<Object[]> legacyHistory = repository.getLegacyAggregatedHistory(DAY);
        assertThat(legacyHistory).hasSize(2);
        assertThat(legacyHistory.get(0)[0]).isEqualTo(DAY);
        assertThat(((Number) legacyHistory.get(0)[1]).doubleValue()).isEqualTo(17.0);
        assertThat(((Number) legacyHistory.get(0)[2]).longValue()).isEqualTo(5L);
        assertThat(legacyHistory.get(1)[0]).isEqualTo(DAY.plusDays(1));

        // The history finder still returns them: its callers label each point legacy.
        assertThat(repository.findByAccountIdAndDateBetweenOrderByDateAsc(accountA.getId(), DAY, DAY.plusDays(1)))
                .hasSize(2);
    }

    @Test
    void aTestOnlyNonLegacyRow_isTheOnlyRowTheRealizedQueriesReturn() {
        DailySpotSavings verified = row(accountA, DAY, 40.0, 1);
        row(accountA, DAY.plusDays(1), 9.0, 1);
        markNonLegacy(verified);

        assertThat(repository.getTotalSavings()).isEqualTo(40.0);
        assertThat(repository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(
                accountA.getId(), DAY, DAY.plusDays(1)))
                .extracting(DailySpotSavings::getAmountSaved).containsExactly(40.0);
        List<Object[]> realized = repository.getAggregatedHistory(DAY);
        assertThat(realized).hasSize(1);
        assertThat(realized.get(0)[0]).isEqualTo(DAY);
        assertThat(((Number) realized.get(0)[1]).doubleValue()).isEqualTo(40.0);

        // ...and it is no longer counted as legacy.
        assertThat(repository.getLegacyTotalSavings()).isEqualTo(9.0);
        assertThat(repository.sumLegacyAmount(List.of(accountA.getId()), DAY, DAY.plusDays(1))).isEqualTo(9.0);
        assertThat(repository.getLegacyAggregatedHistory(DAY)).hasSize(1);
    }
}
