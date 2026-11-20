package com.xammer.cloud.service.autospotting.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerEntry;
import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLine;
import com.xammer.cloud.repository.autospotting.ledger.SavingsLedgerEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.SavingsStatementLineRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The recompute tool (design-ledger.md §14.1, AC-LED-17; plan-phase-16.md task 16.4). Read only: it
 * reloads an entry's stored {@code inputs}, runs the entry's {@code calc_version} on them, and diffs
 * every money field and every statement line that cites the entry. A clean entry has no diff. A
 * changed stored input, or a stored amount that no longer matches, shows as a diff.
 *
 * <p>Money fields are compared exactly (scale 10, {@code compareTo}); each diff also says whether the
 * two values still differ at the currency's minor unit, which is what a customer would see. Statement
 * lines are compared at the minor unit for {@code billable_amount} and at scale 10 for the other
 * amounts. Nothing is written.
 *
 * <p>CI calls {@link #recompute(StoredEntry, List, Map)} over every golden fixture with the fixture's own
 * expected entry as the stored one.
 */
@Service
public class LedgerRecomputeCommand {
    private static final TypeReference<List<EntryCalc.Exclusion>> EXCLUSIONS = new TypeReference<>() {};

    private final SavingsLedgerEntryRepository entries;
    private final SavingsStatementLineRepository lines;

    public LedgerRecomputeCommand(SavingsLedgerEntryRepository entries, SavingsStatementLineRepository lines) {
        this.entries = entries;
        this.lines = lines;
    }

    /** A stored entry as the diff sees it: the persisted fields, with no JPA type in the way. */
    public record StoredEntry(String id, String calcVersion, String inputsJson, String inputsSha256, String tier,
                              boolean billable, String nonBillableReason, List<String> unknownInputs,
                              long instanceSeconds, BigDecimal counterfactualUnitSeconds,
                              BigDecimal counterfactualListCost, BigDecimal effectiveRateAdjustment,
                              BigDecimal counterfactualCost, BigDecimal spotCost, BigDecimal grossSavings,
                              BigDecimal commitmentDeduction, BigDecimal netSavings,
                              BigDecimal netSavingsLowerBound, String exclusionsJson) {
        public static StoredEntry from(SavingsLedgerEntry e) {
            return new StoredEntry(e.getId().toString(), e.getCalcVersion(), e.getInputs(), e.getInputsSha256(),
                    e.getTier(), e.isBillable(), e.getNonBillableReason(), Arrays.asList(e.getUnknownInputs()),
                    e.getInstanceSeconds(), e.getCounterfactualUnitSeconds(), e.getCounterfactualListCost(),
                    e.getEffectiveRateAdjustment(), e.getCounterfactualCost(), e.getSpotCost(), e.getGrossSavings(),
                    e.getCommitmentDeduction(), e.getNetSavings(), e.getNetSavingsLowerBound(), e.getExclusions());
        }
    }

    /** A stored GROUP_PERIOD line as the diff sees it. */
    public record StoredLine(String statementId, int lineNo, String tier, boolean billable, String nonBillableReason,
                             BigDecimal instanceHours, BigDecimal counterfactualCost, BigDecimal spotCost,
                             BigDecimal commitmentDeduction, BigDecimal rawNet, BigDecimal billableAmount,
                             List<String> entryIds) {
        public static StoredLine from(SavingsStatementLine l) {
            return new StoredLine(l.getStatementId().toString(), l.getLineNo(), l.getTier(), l.isBillable(),
                    l.getNonBillableReason(), l.getInstanceHours(), l.getCounterfactualCost(), l.getSpotCost(),
                    l.getCommitmentDeduction(), l.getRawNet(), l.getBillableAmount(),
                    Arrays.stream(l.getEntryIds()).map(UUID::toString).toList());
        }
    }

    /** One differing field: its stored and recomputed text, and whether the minor-unit values differ too. */
    public record FieldDiff(String scope, String field, String stored, String recomputed, boolean minorUnitDiffers) {}

    /** The outcome for one entry: {@code diffs} is empty when the entry recomputes exactly. */
    public record Result(String entryId, String calcVersion, boolean zeroDiff, List<FieldDiff> diffs) {}

    /** Recomputes a persisted entry and the lines that cite it. */
    public Result recompute(UUID entryId) {
        SavingsLedgerEntry entity = entries.findById(entryId).orElseThrow(() -> new AutoSpottingApiException(
                HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", "No ledger entry with that id."));
        List<StoredLine> stored = lines.findReferencing(entryId).stream()
                .filter(l -> "GROUP_PERIOD".equals(l.getLineKind())).map(StoredLine::from).toList();
        return recompute(StoredEntry.from(entity), stored, this::loadSiblingEntry);
    }

    /**
     * Recomputes a stored entry and the given stored lines without a database (the golden-fixture path).
     * {@code siblings} holds the other entries the lines cite, by id.
     */
    public Result recompute(StoredEntry stored, List<StoredLine> storedLines, Map<String, StoredEntry> siblings) {
        return recompute(stored, storedLines, id -> Optional.ofNullable(siblings.get(id)));
    }

    private Optional<StoredEntry> loadSiblingEntry(String id) {
        return entries.findById(UUID.fromString(id)).map(StoredEntry::from);
    }

    private Result recompute(StoredEntry stored, List<StoredLine> storedLines,
                             java.util.function.Function<String, Optional<StoredEntry>> siblings) {
        List<FieldDiff> diffs = new ArrayList<>();
        CalcInputs inputs = parse(stored);
        Optional<EntryCalc> recomputed = run(stored.calcVersion(), inputs);
        diffEntry("entry", stored, recomputed, inputs, diffs);
        for (StoredLine line : storedLines) {
            diffLine(line, stored, recomputed, inputs, siblings, diffs);
        }
        return new Result(stored.id(), stored.calcVersion(), diffs.isEmpty(), List.copyOf(diffs));
    }

    private static CalcInputs parse(StoredEntry stored) {
        try {
            return CanonicalJson.read(stored.inputsJson(), CalcInputs.class);
        } catch (UncheckedIOException e) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INPUTS_UNREADABLE",
                    "The stored inputs of the entry cannot be read.");
        }
    }

    /** Runs the entry's own calc version; a version this build does not know is refused, never guessed. */
    private static Optional<EntryCalc> run(String calcVersion, CalcInputs inputs) {
        if (!SavingsCalculator.VERSION.equals(calcVersion) && !SavingsCalculator.GCP_VERSION.equals(calcVersion)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CALC_VERSION_UNKNOWN",
                    "No calculator for calc version " + calcVersion + " in this build.");
        }
        return new SavingsCalculator().calculate(inputs);
    }

    // ---------------------------------------------------------------- entry

    private static void diffEntry(String scope, StoredEntry s, Optional<EntryCalc> recomputed, CalcInputs inputs,
                                  List<FieldDiff> out) {
        String hash = CanonicalJson.hash(inputs);
        text(out, scope, "inputs_sha256", s.inputsSha256(), hash);
        if (recomputed.isEmpty()) {
            out.add(new FieldDiff(scope, "entry", "present", "no entry earned", true));
            return;
        }
        EntryCalc r = recomputed.get();
        text(out, scope, "tier", s.tier(), r.tier().name());
        text(out, scope, "billable", String.valueOf(s.billable()), String.valueOf(r.billable()));
        text(out, scope, "non_billable_reason", s.nonBillableReason(), r.nonBillableReason());
        text(out, scope, "unknown_inputs", String.join(",", s.unknownInputs()), String.join(",", r.unknownInputs()));
        text(out, scope, "instance_seconds", String.valueOf(s.instanceSeconds()), String.valueOf(r.instanceSeconds()));
        money(out, scope, "counterfactual_unit_seconds", s.counterfactualUnitSeconds(), r.counterfactualUnitSeconds());
        money(out, scope, "counterfactual_list_cost", s.counterfactualListCost(), r.counterfactualListCost());
        money(out, scope, "effective_rate_adjustment", s.effectiveRateAdjustment(), r.effectiveRateAdjustment());
        money(out, scope, "counterfactual_cost", s.counterfactualCost(), r.counterfactualCost());
        money(out, scope, "spot_cost", s.spotCost(), r.spotCost());
        money(out, scope, "gross_savings", s.grossSavings(), r.grossSavings());
        money(out, scope, "commitment_deduction", s.commitmentDeduction(), r.commitmentDeduction());
        money(out, scope, "net_savings", s.netSavings(), r.netSavings());
        money(out, scope, "net_savings_lower_bound", s.netSavingsLowerBound(), r.netSavingsLowerBound());
        text(out, scope, "exclusions", canonicalExclusions(s.exclusionsJson()), CanonicalJson.compact(r.exclusions()));
    }

    private static String canonicalExclusions(String json) {
        if (json == null) {
            return null;
        }
        try {
            List<EntryCalc.Exclusion> parsed = CanonicalJson.mapper().readValue(json, EXCLUSIONS);
            return CanonicalJson.compact(parsed);
        } catch (IOException e) {
            return json;
        }
    }

    // ---------------------------------------------------------------- line

    private void diffLine(StoredLine line, StoredEntry self, Optional<EntryCalc> selfRecomputed, CalcInputs selfInputs,
                          java.util.function.Function<String, Optional<StoredEntry>> siblings, List<FieldDiff> out) {
        String scope = "line " + line.statementId() + "#" + line.lineNo();
        List<EntryCalc> group = new ArrayList<>();
        boolean contractApplies = true;
        for (String id : line.entryIds()) {
            EntryCalc calc;
            CalcInputs in;
            if (id.equals(self.id())) {
                if (selfRecomputed.isEmpty()) {
                    out.add(new FieldDiff(scope, "entry " + id, "present", "no entry earned", true));
                    return;
                }
                calc = selfRecomputed.get();
                in = selfInputs;
            } else {
                Optional<StoredEntry> other = siblings.apply(id);
                if (other.isEmpty()) {
                    out.add(new FieldDiff(scope, "entry " + id, "cited", "not found", true));
                    return;
                }
                in = parse(other.get());
                Optional<EntryCalc> again = run(other.get().calcVersion(), in);
                if (again.isEmpty()) {
                    out.add(new FieldDiff(scope, "entry " + id, "present", "no entry earned", true));
                    return;
                }
                calc = again.get();
            }
            group.add(calc);
            contractApplies &= in.contractCovers();
        }
        StatementLineMath.Line expected = StatementLineMath.build(group, contractApplies);
        text(out, scope, "tier", line.tier(), expected.tier().name());
        text(out, scope, "billable", String.valueOf(line.billable()), String.valueOf(expected.billable()));
        text(out, scope, "non_billable_reason", line.nonBillableReason(), expected.nonBillableReason());
        money(out, scope, "instance_hours", line.instanceHours(), expected.instanceHours());
        money(out, scope, "counterfactual_cost", line.counterfactualCost(), expected.counterfactualCost());
        money(out, scope, "spot_cost", line.spotCost(), expected.spotCost());
        money(out, scope, "commitment_deduction", line.commitmentDeduction(), expected.commitmentDeduction());
        money(out, scope, "raw_net", line.rawNet(), expected.rawNet());
        BigDecimal stored = line.billableAmount() == null ? null : line.billableAmount().setScale(2, RoundingMode.HALF_UP);
        if (stored == null || stored.compareTo(expected.billableAmount()) != 0) {
            out.add(new FieldDiff(scope, "billable_amount", plain(line.billableAmount()),
                    plain(expected.billableAmount()), true));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void text(List<FieldDiff> out, String scope, String field, String stored, String recomputed) {
        if (!Objects.equals(stored == null ? "" : stored, recomputed == null ? "" : recomputed)) {
            out.add(new FieldDiff(scope, field, stored, recomputed, true));
        }
    }

    private static void money(List<FieldDiff> out, String scope, String field, BigDecimal stored, BigDecimal recomputed) {
        if (stored == null && recomputed == null) {
            return;
        }
        if (stored == null || recomputed == null || stored.compareTo(recomputed) != 0) {
            boolean minor = stored == null || recomputed == null
                    || stored.setScale(2, RoundingMode.HALF_UP).compareTo(recomputed.setScale(2, RoundingMode.HALF_UP)) != 0;
            out.add(new FieldDiff(scope, field, plain(stored), plain(recomputed), minor));
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
