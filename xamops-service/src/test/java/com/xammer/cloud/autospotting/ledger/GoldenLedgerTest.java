package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.autospotting.ledger.GoldenFixtures.Output;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;

import java.io.IOException;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden fixtures (design-ledger.md §14.1, AC-LED-01; plan-phase-16.md task 16.2). Each fixture's
 * {@code inputs.json} runs through the calculator and the test-only line builder; the canonical
 * serialization is compared byte for byte with {@code entries.expected.json} and
 * {@code lines.expected.json}. The JVM zone is set to Asia/Kolkata for the whole class (and to two
 * more zones in {@link #theJvmZoneNeverLeaksIn()}), so a zone leak changes bytes.
 *
 * <p>Regenerating a file is a deliberate act: {@code -Dledger.golden.regen=true} rewrites the expected
 * files from the current output, and the diff in review is the check. Expected files are reviewed
 * against the worked numbers in {@code SavingsCalculatorTest} and the evidence file.
 */
class GoldenLedgerTest {
    private static TimeZone original;

    @BeforeAll
    static void kolkata() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterAll
    static void restore() {
        TimeZone.setDefault(original);
    }

    @TestFactory
    Stream<DynamicTest> everyFixtureMatchesItsGoldenFilesByteForByte() throws IOException {
        boolean regen = Boolean.getBoolean("ledger.golden.regen");
        return GoldenFixtures.names().stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Output out = GoldenFixtures.run(GoldenFixtures.inputs(name));
            String entries = CanonicalJson.pretty(out.entries());
            String lines = CanonicalJson.pretty(out.lines());
            if (regen) {
                GoldenFixtures.write(name, "entries.expected.json", entries);
                GoldenFixtures.write(name, "lines.expected.json", lines);
                return;
            }
            assertThat(entries).as(name + " entries").isEqualTo(GoldenFixtures.read(name, "entries.expected.json"));
            assertThat(lines).as(name + " lines").isEqualTo(GoldenFixtures.read(name, "lines.expected.json"));
        }));
    }

    @Test
    void theFixtureSetCoversEveryPhase16Case() throws IOException {
        assertThat(GoldenFixtures.names()).containsExactlyInAnyOrder(
                "aws-steady", "aws-sp-discounted-commitment", "aws-autoscale", "aws-overlap", "aws-floor",
                "aws-fallback-window", "aws-revert-window", "aws-unknown-price", "aws-commitment",
                "aws-suse-hourly", "aws-90min", "aws-weighted", "aws-preexisting-spot", "aws-negative",
                "aws-lower-of", "aws-interruption-cause-unknown", "aws-ri-lower-of", "aws-45s-instance",
                "paused-account-switch", "legacy-unconfirmed", "gone-mid-day", "annex-external-and-unmanaged",
                "dst-kolkata", "contract-mid-month", "before-contract", "no-fee-account",
                "ce-net-equals-unblended", "ce-net-discounted", "discount-not-declared", "switch-d-effective-at",
                "fallback-effective-at", "first-conversion-mid-month", "empty-run-day",
                "switch-d-recovery-same-sweep", "switch-d-degrade-same-sweep", "switch-d-compensated-to-active",
                "state-order-violation", "revert-failed-before-mutation", "switch-d-stale-cache",
                "revert-not-started-startup",
                "gcp-steady", "gcp-preempt-first-minute", "gcp-sud-export", "gcp-sud-max", "gcp-sud-observed-wins",
                "gcp-sud-complementary", "gcp-cud-partial", "gcp-e2-small-shared-core", "gcp-csp-dataset-pending",
                "duplicate-project");
    }

    @Test
    void theJvmZoneNeverLeaksIn() throws IOException {
        TimeZone saved = TimeZone.getDefault();
        try {
            for (String name : List.of("dst-kolkata", "aws-steady", "first-conversion-mid-month")) {
                String reference = CanonicalJson.pretty(GoldenFixtures.run(GoldenFixtures.inputs(name)).entries());
                for (String zone : List.of("UTC", "America/New_York", "Pacific/Kiritimati", "Asia/Kolkata")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zone));
                    assertThat(CanonicalJson.pretty(GoldenFixtures.run(GoldenFixtures.inputs(name)).entries()))
                            .as(name + " in " + zone).isEqualTo(reference);
                }
            }
        } finally {
            TimeZone.setDefault(saved);
        }
    }
}
