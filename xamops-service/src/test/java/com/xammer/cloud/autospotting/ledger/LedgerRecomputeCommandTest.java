package com.xammer.cloud.autospotting.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.autospotting.ledger.GoldenFixtures.GoldenInputs;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerEntry;
import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLine;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsStatementLineRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.FieldDiff;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.Result;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.StoredEntry;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.StoredLine;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The recompute tool (design-ledger.md §14.1, AC-LED-17; plan-phase-16.md task 16.4): zero diff on every
 * golden fixture, with the fixture's own expected entries and lines as the "stored" ones; a tampered
 * stored input or amount shows as a diff; an unknown calc version is refused; and the repository-backed
 * path reads and writes nothing.
 */
class LedgerRecomputeCommandTest {
    private static final TypeReference<List<EntryCalc>> ENTRIES = new TypeReference<>() {};
    private static final TypeReference<List<StatementLineMath.Line>> LINES = new TypeReference<>() {};

    private final LedgerRecomputeCommand command = new LedgerRecomputeCommand(
            mock(SavingsLedgerEntryRepository.class), mock(SavingsStatementLineRepository.class));

    /** A fixture's stored side: one stored entry per expected entry (with its day's inputs), and its lines. */
    private record Stored(List<StoredEntry> entries, Map<String, StoredEntry> byId, List<StoredLine> lines,
                          List<String> groupMonthOfEntry) {}

    private static Stored stored(String name) throws IOException {
        GoldenInputs inputs = GoldenFixtures.inputs(name);
        List<EntryCalc> expected = CanonicalJson.mapper().readValue(GoldenFixtures.read(name, "entries.expected.json"), ENTRIES);
        List<StatementLineMath.Line> lines = CanonicalJson.mapper().readValue(GoldenFixtures.read(name, "lines.expected.json"), LINES);
        List<StoredEntry> entries = new ArrayList<>();
        Map<String, StoredEntry> byId = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        int next = 0;
        for (CalcInputs day : inputs.days()) {
            Optional<EntryCalc> any = new SavingsCalculator().calculate(day);
            if (any.isEmpty()) {
                continue;
            }
            EntryCalc e = expected.get(next++);
            String id = UUID.nameUUIDFromBytes((name + e.periodStart()).getBytes()).toString();
            StoredEntry s = new StoredEntry(id, e.calcVersion(), CanonicalJson.compact(day), CanonicalJson.hash(day),
                    e.tier().name(), e.billable(), e.nonBillableReason(), e.unknownInputs(), e.instanceSeconds(),
                    e.counterfactualUnitSeconds(), e.counterfactualListCost(), e.effectiveRateAdjustment(),
                    e.counterfactualCost(), e.spotCost(), e.grossSavings(), e.commitmentDeduction(), e.netSavings(),
                    e.netSavingsLowerBound(), CanonicalJson.compact(e.exclusions()));
            entries.add(s);
            byId.put(id, s);
            keys.add(e.groupId() + "|" + e.statementMonth());
        }
        assertThat(next).as(name + ": every expected entry was matched").isEqualTo(expected.size());
        List<StoredLine> storedLines = new ArrayList<>();
        int lineNo = 1;
        for (StatementLineMath.Line l : lines) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (keys.get(i).equals(l.groupId() + "|" + l.statementMonth())) {
                    ids.add(entries.get(i).id());
                }
            }
            storedLines.add(new StoredLine("00000000-0000-4000-8000-000000000001", lineNo++, l.tier().name(), l.billable(),
                    l.nonBillableReason(), l.instanceHours(), l.counterfactualCost(), l.spotCost(),
                    l.commitmentDeduction(), l.rawNet(), l.billableAmount(), ids));
        }
        return new Stored(entries, byId, storedLines, keys);
    }

    @TestFactory
    Stream<DynamicTest> everyGoldenFixtureRecomputesWithZeroDiff() throws IOException {
        return GoldenFixtures.names().stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            Stored s = stored(name);
            for (StoredEntry entry : s.entries()) {
                List<StoredLine> citing = s.lines().stream().filter(l -> l.entryIds().contains(entry.id())).toList();
                Result r = command.recompute(entry, citing, s.byId());
                assertThat(r.diffs()).as(name + " " + entry.id()).isEmpty();
                assertThat(r.zeroDiff()).isTrue();
            }
        }));
    }

    @Test
    void aTamperedStoredInputShowsTheDiff() throws IOException {
        Stored s = stored("aws-steady");
        StoredEntry entry = s.entries().get(0);
        String tampered = entry.inputsJson().replace("0.0570000000", "0.0580000000");
        assertThat(tampered).isNotEqualTo(entry.inputsJson());
        StoredEntry changed = new StoredEntry(entry.id(), entry.calcVersion(), tampered, entry.inputsSha256(), entry.tier(),
                entry.billable(), entry.nonBillableReason(), entry.unknownInputs(), entry.instanceSeconds(),
                entry.counterfactualUnitSeconds(), entry.counterfactualListCost(), entry.effectiveRateAdjustment(),
                entry.counterfactualCost(), entry.spotCost(), entry.grossSavings(), entry.commitmentDeduction(),
                entry.netSavings(), entry.netSavingsLowerBound(), entry.exclusionsJson());
        Result r = command.recompute(changed, List.of(), Map.of());
        assertThat(r.zeroDiff()).isFalse();
        assertThat(r.diffs()).extracting(FieldDiff::field).contains("inputs_sha256", "spot_cost", "gross_savings", "net_savings");
        FieldDiff spot = r.diffs().stream().filter(d -> d.field().equals("spot_cost")).findFirst().orElseThrow();
        assertThat(spot.stored()).isEqualTo("4.1040000000");
        assertThat(spot.recomputed()).isEqualTo("4.1760000000");   // 0.058 x 259200 / 3600
        assertThat(spot.minorUnitDiffers()).isTrue();
    }

    @Test
    void aTamperedStoredAmountShowsTheDiff_andSaysWhetherTheMinorUnitDiffers() throws IOException {
        Stored s = stored("aws-steady");
        StoredEntry e = s.entries().get(0);
        StoredEntry sub = new StoredEntry(e.id(), e.calcVersion(), e.inputsJson(), e.inputsSha256(), e.tier(), e.billable(),
                e.nonBillableReason(), e.unknownInputs(), e.instanceSeconds(), e.counterfactualUnitSeconds(),
                e.counterfactualListCost(), e.effectiveRateAdjustment(), e.counterfactualCost(),
                e.spotCost().add(new BigDecimal("0.0000000001")), e.grossSavings(), e.commitmentDeduction(),
                e.netSavings(), e.netSavingsLowerBound(), e.exclusionsJson());
        Result r = command.recompute(sub, List.of(), Map.of());
        assertThat(r.diffs()).hasSize(1);
        assertThat(r.diffs().get(0).field()).isEqualTo("spot_cost");
        assertThat(r.diffs().get(0).minorUnitDiffers()).isFalse();
        StoredEntry cents = new StoredEntry(e.id(), e.calcVersion(), e.inputsJson(), e.inputsSha256(), e.tier(), e.billable(),
                e.nonBillableReason(), e.unknownInputs(), e.instanceSeconds(), e.counterfactualUnitSeconds(),
                e.counterfactualListCost(), e.effectiveRateAdjustment(), e.counterfactualCost(),
                e.spotCost().add(new BigDecimal("0.0100000000")), e.grossSavings(), e.commitmentDeduction(),
                e.netSavings(), e.netSavingsLowerBound(), e.exclusionsJson());
        assertThat(command.recompute(cents, List.of(), Map.of()).diffs().get(0).minorUnitDiffers()).isTrue();
    }

    @Test
    void aTamperedStatementLineShowsTheDiff() throws IOException {
        Stored s = stored("aws-steady");
        StoredLine line = s.lines().get(0);
        StoredLine changed = new StoredLine(line.statementId(), line.lineNo(), line.tier(), line.billable(),
                line.nonBillableReason(), line.instanceHours(), line.counterfactualCost(), line.spotCost(),
                line.commitmentDeduction(), line.rawNet(), new BigDecimal("19.45"), line.entryIds());
        Result r = command.recompute(s.entries().get(0), List.of(changed), s.byId());
        assertThat(r.diffs()).hasSize(1);
        assertThat(r.diffs().get(0).field()).isEqualTo("billable_amount");
        assertThat(r.diffs().get(0).stored()).isEqualTo("19.45");
        assertThat(r.diffs().get(0).recomputed()).isEqualTo("19.44");
    }

    @Test
    void anUnknownCalcVersionIsRefused_notGuessed() throws IOException {
        StoredEntry e = stored("aws-steady").entries().get(0);
        StoredEntry other = new StoredEntry(e.id(), "aws-9", e.inputsJson(), e.inputsSha256(), e.tier(), e.billable(),
                e.nonBillableReason(), e.unknownInputs(), e.instanceSeconds(), e.counterfactualUnitSeconds(),
                e.counterfactualListCost(), e.effectiveRateAdjustment(), e.counterfactualCost(), e.spotCost(),
                e.grossSavings(), e.commitmentDeduction(), e.netSavings(), e.netSavingsLowerBound(), e.exclusionsJson());
        assertThatThrownBy(() -> command.recompute(other, List.of(), Map.of()))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("CALC_VERSION_UNKNOWN"));
    }

    @Test
    void anEntryTheCalculatorNoLongerEarns_showsAsADiff() throws IOException {
        StoredEntry e = stored("aws-steady").entries().get(0);
        CalcInputs in = CanonicalJson.read(e.inputsJson(), CalcInputs.class);
        CalcInputs none = new CalcInputs(in.calcVersion(), in.provider(), in.currency(), in.windowStart(), in.windowEnd(),
                in.group(), in.goLiveAt(), in.contract(), in.feeBasis(), in.reconciledTier(), List.of(), in.revertOps(),
                in.intervals(), in.capacitySamples(), in.spotPrices(), in.odPrices(), in.original(), in.commitmentsStatus(),
                in.commitments());
        StoredEntry changed = new StoredEntry(e.id(), e.calcVersion(), CanonicalJson.compact(none), e.inputsSha256(), e.tier(),
                e.billable(), e.nonBillableReason(), e.unknownInputs(), e.instanceSeconds(), e.counterfactualUnitSeconds(),
                e.counterfactualListCost(), e.effectiveRateAdjustment(), e.counterfactualCost(), e.spotCost(), e.grossSavings(),
                e.commitmentDeduction(), e.netSavings(), e.netSavingsLowerBound(), e.exclusionsJson());
        assertThat(command.recompute(changed, List.of(), Map.of()).diffs()).extracting(FieldDiff::field).contains("entry");
    }

    // ---------------------------------------------------------------- the persisted path

    @Test
    void recomputeById_readsTheEntryAndTheLinesThatCiteIt_andWritesNothing() throws IOException {
        SavingsLedgerEntryRepository entries = mock(SavingsLedgerEntryRepository.class);
        SavingsStatementLineRepository lines = mock(SavingsStatementLineRepository.class);
        StoredEntry s = stored("aws-steady").entries().get(0);
        UUID id = UUID.fromString(s.id());
        when(entries.findById(id)).thenReturn(Optional.of(entity(id, s)));
        when(lines.findReferencing(id)).thenReturn(List.of());
        Result r = new LedgerRecomputeCommand(entries, lines).recompute(id);
        assertThat(r.zeroDiff()).isTrue();
        assertThat(r.entryId()).isEqualTo(id.toString());
        org.mockito.Mockito.verify(entries).findById(id);
        org.mockito.Mockito.verifyNoMoreInteractions(entries);
    }

    @Test
    void recomputeById_notFound_is404() {
        SavingsLedgerEntryRepository entries = mock(SavingsLedgerEntryRepository.class);
        when(entries.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new LedgerRecomputeCommand(entries, mock(SavingsStatementLineRepository.class)).recompute(UUID.randomUUID()))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ENTRY_NOT_FOUND");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
    }

    private static SavingsLedgerEntry entity(UUID id, StoredEntry s) {
        SavingsLedgerEntry e = new SavingsLedgerEntry();
        e.setId(id);
        e.setEntryType("ACCRUAL");
        e.setProvider(Provider.AWS);
        e.setCalcVersion(s.calcVersion());
        e.setTier(s.tier());
        e.setBillable(s.billable());
        e.setNonBillableReason(s.nonBillableReason());
        e.setUnknownInputs(s.unknownInputs().toArray(new String[0]));
        e.setInstanceSeconds(s.instanceSeconds());
        e.setCounterfactualUnitSeconds(s.counterfactualUnitSeconds());
        e.setCounterfactualListCost(s.counterfactualListCost());
        e.setEffectiveRateAdjustment(s.effectiveRateAdjustment());
        e.setCounterfactualCost(s.counterfactualCost());
        e.setSpotCost(s.spotCost());
        e.setGrossSavings(s.grossSavings());
        e.setCommitmentDeduction(s.commitmentDeduction());
        e.setNetSavings(s.netSavings());
        e.setNetSavingsLowerBound(s.netSavingsLowerBound());
        e.setExclusions(s.exclusionsJson());
        e.setInputs(s.inputsJson());
        e.setInputsSha256(s.inputsSha256());
        return e;
    }

    @Test
    void storedLineFromEntity_roundTripsItsFields() {
        SavingsStatementLine l = new SavingsStatementLine();
        UUID statement = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        l.setStatementId(statement);
        l.setLineNo(2);
        l.setTier("BILL_VERIFIED");
        l.setBillable(true);
        l.setInstanceHours(new BigDecimal("72.000000"));
        l.setCounterfactualCost(new BigDecimal("13.8240000000"));
        l.setSpotCost(new BigDecimal("4.1040000000"));
        l.setCommitmentDeduction(BigDecimal.ZERO.setScale(10));
        l.setRawNet(new BigDecimal("9.7200000000"));
        l.setBillableAmount(new BigDecimal("9.72"));
        l.setEntryIds(new UUID[] {entry});
        StoredLine s = StoredLine.from(l);
        assertThat(s.statementId()).isEqualTo(statement.toString());
        assertThat(s.entryIds()).containsExactly(entry.toString());
        assertThat(s.billableAmount()).isEqualByComparingTo("9.72");
    }
}
