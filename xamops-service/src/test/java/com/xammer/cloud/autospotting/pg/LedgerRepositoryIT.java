package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerEntry;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerRun;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerGoLiveRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger entities and append-only repositories against the migrated schema (plan-phase-15.md task
 * 15.2): {@code save} inserts, decimals round-trip with all ten places, a negative net is stored raw,
 * a loaded row cannot be updated or removed through JPA (and the trigger is behind that), a second
 * {@code save} of a persisted row fails, and the contract query finds the contract in force.
 */
@Tag("containers")
class LedgerRepositoryIT {

    @Test
    void repositoriesInsertExactDecimals_andRefuseUpdate() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            Map<String, Object> properties = Map.of(
                    "spring.datasource.url", db.jdbcUrl(),
                    "spring.datasource.username", db.username(),
                    "spring.datasource.password", db.password(),
                    "spring.jpa.hibernate.ddl-auto", "validate",
                    "spring.jpa.open-in-view", "false",
                    "spring.flyway.enabled", "false");
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-it", properties));
                context.register(LedgerJpa.class);
                context.refresh();
                TransactionTemplate tx = new TransactionTemplate(
                        context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                SavingsLedgerRunRepository runs = context.getBean(SavingsLedgerRunRepository.class);
                SavingsLedgerEntryRepository entries = context.getBean(SavingsLedgerEntryRepository.class);
                AutoSpottingContractRepository contracts = context.getBean(AutoSpottingContractRepository.class);
                SavingsLedgerGoLiveRepository goLives = context.getBean(SavingsLedgerGoLiveRepository.class);
                EntityManager em = context.getBean(EntityManager.class);

                UUID runId = UUID.randomUUID();
                UUID entryId = UUID.randomUUID();
                tx.executeWithoutResult(s -> {
                    SavingsLedgerRun run = new SavingsLedgerRun();
                    run.setId(runId);
                    run.setProvider(Provider.AWS);
                    run.setCloudAccountId(7L);
                    run.setWindowStart(Instant.parse("2026-11-01T00:00:00Z"));
                    run.setWindowEnd(Instant.parse("2026-11-02T00:00:00Z"));
                    run.setCalcVersion("L1.0.0");
                    run.setInputsSha256("h");
                    run.setTrigger("DAILY");
                    run.setJobLeaseName("ledger-writer");
                    run.setLeaseGeneration(1L);
                    run.setCreatedAt(Instant.now());
                    runs.save(run);
                    entries.save(entry(entryId, runId, "-3.1234567891"));
                });
                SavingsLedgerEntry loaded = call(tx, () -> entries.findById(entryId).orElseThrow());
                assertThat(loaded.getNetSavings().toPlainString()).isEqualTo("-3.1234567891");
                assertThat(loaded.getGrossSavings().toPlainString()).isEqualTo("-3.1234567891");
                assertThat(loaded.getCurrency()).isEqualTo("USD");
                assertThat(loaded.getUnknownInputs()).isEmpty();
                assertThat(loaded.isNew()).isFalse();

                // A loaded row cannot be changed or removed through JPA.
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    SavingsLedgerEntry e = entries.findById(entryId).orElseThrow();
                    e.setSpotCost(new BigDecimal("1.0000000000"));
                    em.flush();
                })).hasMessageContaining("append-only");
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    em.remove(em.find(SavingsLedgerEntry.class, entryId));
                    em.flush();
                })).hasMessageContaining("append-only");
                // Saving a row that already exists is an insert that fails, never an update.
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    entries.save(entry(entryId, runId, "1.0000000000"));
                    em.flush();
                })).isInstanceOf(RuntimeException.class);
                assertThat(call(tx, () -> entries.findById(entryId).orElseThrow().getNetSavings().toPlainString()))
                        .isEqualTo("-3.1234567891");

                // Go-live is stored once, by provider.
                tx.executeWithoutResult(s -> {
                    SavingsLedgerGoLive g = new SavingsLedgerGoLive();
                    g.setProvider("AWS");
                    g.setGoLiveAt(Instant.parse("2026-11-20T00:00:00Z"));
                    g.setSetBy("5");
                    g.setSetAt(Instant.now());
                    goLives.save(g);
                });
                assertThat(call(tx, () -> goLives.existsById("AWS"))).isTrue();

                // The contract in force at an instant: from inclusive, to exclusive.
                Instant from = Instant.now().plus(Duration.ofDays(1));
                Instant to = from.plus(Duration.ofDays(30));
                UUID contractId = UUID.randomUUID();
                tx.executeWithoutResult(s -> {
                    AutoSpottingContract c = new AutoSpottingContract();
                    c.setId(contractId);
                    c.setClientId(3L);
                    c.setSharePct(new BigDecimal("12.123456"));
                    c.setMonthlyCap(new BigDecimal("999999999.99"));
                    c.setEffectiveFrom(from);
                    c.setEffectiveTo(to);
                    c.setCreatedBy("5");
                    c.setCreatedAt(Instant.now());
                    contracts.saveAndFlush(c);
                });
                assertThat(call(tx, () -> contracts.findInForce(3L, from.minusSeconds(1)))).isEmpty();
                assertThat(call(tx, () -> contracts.findInForce(3L, from))).hasSize(1);
                assertThat(call(tx, () -> contracts.findInForce(3L, to.minusSeconds(1)))).hasSize(1);
                assertThat(call(tx, () -> contracts.findInForce(3L, to))).isEmpty();
                assertThat(call(tx, () -> contracts.findInForce(4L, from))).isEmpty();
                AutoSpottingContract stored = call(tx, () -> contracts.findById(contractId).orElseThrow());
                assertThat(stored.getSharePct().toPlainString()).isEqualTo("12.123456");
                assertThat(stored.getMonthlyCap().toPlainString()).isEqualTo("999999999.99");
                assertThat(stored.getCapCurrency()).isEqualTo("USD");
                assertThat(stored.getDeclaredDiscountPct()).isNull();
                // Ending earlier is the one allowed change; changing the share is refused by the trigger.
                tx.executeWithoutResult(s -> {
                    AutoSpottingContract c = contracts.findById(contractId).orElseThrow();
                    c.setEffectiveTo(to.minus(Duration.ofDays(10)));
                    contracts.saveAndFlush(c);
                });
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    AutoSpottingContract c = contracts.findById(contractId).orElseThrow();
                    c.setSharePct(new BigDecimal("50"));
                    contracts.saveAndFlush(c);
                })).hasStackTraceContaining("contract terms are immutable");
            }
        }
    }

    private static <T> T call(TransactionTemplate tx, java.util.function.Supplier<T> supplier) {
        return tx.execute(status -> supplier.get());
    }

    private static SavingsLedgerEntry entry(UUID id, UUID runId, String net) {
        SavingsLedgerEntry e = new SavingsLedgerEntry();
        e.setId(id);
        e.setRunId(runId);
        e.setEntryType("ACCRUAL");
        e.setProvider(Provider.AWS);
        e.setCloudAccountId(7L);
        e.setClientId(3L);
        e.setGroupId(UUID.randomUUID());
        e.setPeriodStart(Instant.parse("2026-11-01T00:00:00Z"));
        e.setPeriodEnd(Instant.parse("2026-11-02T00:00:00Z"));
        e.setStatementMonth(LocalDate.parse("2026-11-01"));
        e.setCalcVersion("L1.0.0");
        e.setTier("MEASURED_ONLY");
        e.setReconciliationStatus("PENDING");
        e.setBillable(false);
        e.setNonBillableReason("MEASURED_ONLY");
        e.setCurrency("USD");
        e.setInstanceSeconds(3600L);
        e.setCounterfactualUnitSeconds(new BigDecimal("1.000000"));
        BigDecimal netValue = new BigDecimal(net);
        // counterfactual 2 minus spot (2 - net) gives gross = net; no deduction.
        BigDecimal cf = new BigDecimal("2.0000000000");
        e.setCounterfactualListCost(cf);
        e.setEffectiveRateAdjustment(new BigDecimal("0E-10"));
        e.setCounterfactualCost(cf);
        e.setSpotCost(cf.subtract(netValue));
        e.setGrossSavings(netValue);
        e.setCommitmentDeduction(new BigDecimal("0E-10"));
        e.setNetSavings(netValue);
        e.setSpotCostSource("MEASURED");
        e.setPriceSnapshotIds(new UUID[0]);
        e.setExclusions("[]");
        e.setInputs("{}");
        e.setInputsSha256("h");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @EntityScan(basePackages = "com.xammer.cloud.domain.autospotting")
    @EnableJpaRepositories(basePackages = "com.xammer.cloud.repository.autospotting.ledger")
    static class LedgerJpa {
    }
}
