package com.xammer.cloud.service.autospotting.ledger.calc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.domain.autospotting.GroupState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The canonical input of one accrual calculation: one group and one UTC day (design-ledger.md §3.1,
 * §14.1; plan-phase-16.md task 16.1). It is the {@code inputs} document an entry stores
 * (FR-LED-12), so the recompute tool can run the stored {@code calc_version} on it again and get the
 * same entry. Everything is decimal or integer: rates and weights are {@link BigDecimal}, instants are
 * whole seconds in UTC, and nothing is read from the JVM zone or the clock.
 *
 * <p>Lists are copied, and their order carries no meaning: the calculator sorts what it reads
 * (transitions by {@code id}, intervals by start and id, prices and commitments by their keys), so a
 * writer may load them in any order and get the same entry.
 *
 * @param calcVersion        {@code aws-1}
 * @param provider           {@code AWS}
 * @param currency           {@code USD}: v1 is USD only (PD-01, {@code ck_ss_usd}); a source in another
 *                           currency is converted by the writer or is UNKNOWN before it reaches here
 * @param windowStart        UTC midnight of the accrual day
 * @param windowEnd          the next UTC midnight
 * @param group              identity of the group
 * @param goLiveAt           {@code savings_ledger_golive.go_live_at}; null means the ledger is not live
 * @param contract           the contract in force for the group's statement month, null if none (PO-01)
 * @param feeBasis           the fee basis the plan recorded at confirm (design-ledger.md §3.1)
 * @param reconciledTier     the best tier reconciliation reached for the day; {@code MEASURED_ONLY} until a
 *                           bill import verifies it (Phase 25). The calculator never raises it
 * @param transitions        every {@code spot_group_transition} of the group up to the end of the day
 * @param revertOps          confirmed REVERT plans and their operations (FR-REV-06, DR-04, GR-07)
 * @param intervals          Spot and On-Demand instance intervals that touch the day
 * @param capacitySamples    desired-capacity samples (a sample holds until the next one)
 * @param spotPrices         Spot price snapshots
 * @param odPrices           On-Demand price snapshots with the effective rate (design-ledger.md §5.1)
 * @param original           the pre-enable configuration from the snapshot (design-ledger.md §3.2, §3.3)
 * @param commitmentsStatus  whether commitments were proven absent, are known, or could not be read
 * @param commitments        unused AWS RI and Savings Plan coverage in scope (design-ledger.md §5)
 * @param gcp                the GCP inputs of {@code gcp-1}; null for AWS and left out of the canonical text then, so
 *                           AWS inputs hash as before
 * @param gcp                the GCP-only inputs of {@code gcp-1} (shapes, core and RAM unit prices, SUD, CUD, billing
 *                           source); null for AWS, and left out of the canonical text then, so AWS inputs hash as before
 */
public record CalcInputs(
        String calcVersion,
        String provider,
        String currency,
        Instant windowStart,
        Instant windowEnd,
        Group group,
        Instant goLiveAt,
        Contract contract,
        FeeBasis feeBasis,
        Tier reconciledTier,
        List<Transition> transitions,
        List<RevertOp> revertOps,
        List<Interval> intervals,
        List<CapacitySample> capacitySamples,
        List<SpotPrice> spotPrices,
        List<OdPrice> odPrices,
        Original original,
        CommitmentsStatus commitmentsStatus,
        List<Commitment> commitments,
        @JsonInclude(JsonInclude.Include.NON_NULL) Gcp gcp) {

    /** The AWS shape: no GCP block. */
    public CalcInputs(String calcVersion, String provider, String currency, Instant windowStart, Instant windowEnd,
                      Group group, Instant goLiveAt, Contract contract, FeeBasis feeBasis, Tier reconciledTier,
                      List<Transition> transitions, List<RevertOp> revertOps, List<Interval> intervals,
                      List<CapacitySample> capacitySamples, List<SpotPrice> spotPrices, List<OdPrice> odPrices,
                      Original original, CommitmentsStatus commitmentsStatus, List<Commitment> commitments) {
        this(calcVersion, provider, currency, windowStart, windowEnd, group, goLiveAt, contract, feeBasis,
                reconciledTier, transitions, revertOps, intervals, capacitySamples, spotPrices, odPrices, original,
                commitmentsStatus, commitments, null);
    }

    @JsonCreator
    public CalcInputs {
        transitions = copy(transitions);
        revertOps = copy(revertOps);
        intervals = copy(intervals);
        capacitySamples = copy(capacitySamples);
        spotPrices = copy(spotPrices);
        odPrices = copy(odPrices);
        commitments = copy(commitments);
        if (reconciledTier == null) {
            reconciledTier = Tier.MEASURED_ONLY;
        }
        if (commitmentsStatus == null) {
            commitmentsStatus = CommitmentsStatus.UNAVAILABLE;
        }
    }

    private static <T> List<T> copy(List<T> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    /** True when the group's fee basis is a contract and the statement month has one (design-ledger.md §3.1). */
    public boolean contractCovers() {
        return contractReason() == null;
    }

    /** The commercial reason the group is not billable this month, or null when its contract applies. */
    public String contractReason() {
        if (contract == null) {
            return Reasons.NO_CONTRACT;
        }
        return switch (feeBasis == null ? FeeBasis.NO_FEE_ACCOUNT : feeBasis) {
            case CONTRACT -> null;
            case BEFORE_CONTRACT -> Reasons.BEFORE_CONTRACT;
            case NO_FEE_ACCOUNT -> Reasons.NO_FEE_ACCOUNT;
        };
    }

    public enum Tier { BILL_VERIFIED, AGGREGATE_VERIFIED, MEASURED_ONLY, UNKNOWN;
        public boolean isBillable() {
            return this == BILL_VERIFIED || this == AGGREGATE_VERIFIED;
        }
    }

    public enum FeeBasis { CONTRACT, BEFORE_CONTRACT, NO_FEE_ACCOUNT }

    public enum PurchaseOption { SPOT, ON_DEMAND }

    /** How a finished interval ended; null or {@code UNKNOWN} when no source established it (design-ledger.md §3.5). */
    public enum EndCause { PROVIDER_INTERRUPTED, TERMINATED, UNKNOWN }

    /** How the On-Demand effective rate was established (design-ledger.md §5.1 orders 1 to 4). */
    public enum DiscountBasis { NET_DERIVED, DECLARED, PROVEN_NONE, NONE }

    public enum CommitmentsStatus { NONE_PROVEN, KNOWN, UNAVAILABLE }

    public enum CommitmentKind { RESERVED_INSTANCE, SAVINGS_PLAN }

    public enum CommitmentData { AVAILABLE, RATE_UNKNOWN, UNAVAILABLE }

    public record Group(String groupId, Long cloudAccountId, Long clientId, String region) {}

    /** The contract in force for the statement month: its id and the percentage BillOps applies. */
    public record Contract(String contractId, BigDecimal sharePct) {}

    /**
     * One {@code spot_group_transition} row. {@code id} orders the rows; {@code effectiveAt} places the
     * boundary in time (design-ledger.md §6.2); {@code occurredAt} is informational.
     */
    public record Transition(long id, GroupState fromState, GroupState toState, Instant effectiveAt,
                             Instant occurredAt) {}

    /** A confirmed REVERT plan: confirmed, and the op's end and whether it ever mutated the cloud. */
    public record RevertOp(String opId, Instant confirmedAt, Instant finishedAt, Long firstMutationSeq) {}

    /**
     * An instance interval. {@code endAt} set means a known end. With no {@code endAt} but a
     * {@code firstAbsentAt}, the end is unknown and lies in {@code [lastSeenAt, firstAbsentAt]}
     * (design-ledger.md §3.6). With neither, the instance is still running.
     */
    public record Interval(String intervalId, String instanceId, String instanceType, String zone,
                           String platform, PurchaseOption purchaseOption, BigDecimal capacityWeight,
                           Instant startAt, Instant endAt, Instant lastSeenAt, Instant firstAbsentAt,
                           EndCause endCause) {}

    public record CapacitySample(Instant at, BigDecimal desired) {}

    /** A Spot rate per instance-hour, eligible from {@code effectiveFrom} until {@code effectiveTo} (§4.4). */
    public record SpotPrice(String snapshotId, String instanceType, String zone, String platform,
                            BigDecimal rate, Instant effectiveFrom, Instant effectiveTo) {}

    /** On-Demand list rate and effective rate per instance-hour for a type and platform. */
    public record OdPrice(String snapshotId, String instanceType, String platform, BigDecimal listRate,
                          BigDecimal effectiveRate, DiscountBasis discountBasis) {}

    /**
     * The original configuration (design-ledger.md §3.2): {@code baseUnits} and
     * {@code onDemandPctAboveBase} of the original {@code InstancesDistribution}, and the pre-enable
     * types. An ENABLE'd group has {@code onDemandPctAboveBase = 100}, so {@code P = 0}.
     */
    public record Original(BigDecimal baseUnits, BigDecimal onDemandPctAboveBase, List<PreType> preEnable) {
        public Original {
            preEnable = preEnable == null ? List.of() : List.copyOf(preEnable);
        }
    }

    public record PreType(String instanceType, String platform, BigDecimal weight,
                          BigDecimal normalizationFactor) {}

    /**
     * Unused coverage of one commitment scope (design-ledger.md §5.2, §5.4). {@code unused} is
     * normalized unit-hours for an RI and dollars for a Savings Plan. {@code otherCoverable} is the
     * other groups' coverable amount in the scope in the same units; {@code lastGroup} makes this
     * group take the remainder after {@code allocatedToOthers}.
     */
    public record Commitment(String scopeId, CommitmentKind kind, CommitmentData data, BigDecimal unused,
                             BigDecimal otherCoverable, boolean lastGroup, BigDecimal allocatedToOthers,
                             java.util.Map<String, BigDecimal> planRates) {
        public Commitment {
            planRates = planRates == null ? java.util.Map.of() : java.util.Map.copyOf(planRates);
        }
    }

    // ------------------------------------------------------------------ GCP (calc version gcp-1)

    /**
     * What {@code gcp-1} needs beyond the shared shape (design-ledger.md §3.5, §3.9, §4.2, §5.3, §5.4).
     * Everything is decimal; the calculator derives per-instance-hour rates from the core and RAM unit prices
     * and the shapes, so the stored inputs reproduce the entry.
     *
     * @param billingSource  {@code OWN_EXPORT}, {@code XAMMER_EXPORT} or {@code XAMMER_CSP_DATASET}
     *                       ({@code CloudAccount.resolveBillingSource()}, PD-06); a CSP dataset project stays
     *                       {@code MEASURED_ONLY} with {@code CSP_DATASET_PENDING}
     * @param sharedCoreTable the {@code gcp-shared-core-v1} version the shapes were priced under (PD-09)
     * @param declaredDiscountPct a contract-declared negotiated discount on top of SUD, or null
     * @param shapes         billed vCPU and GiB per machine type in use
     * @param prices         core and RAM unit prices per series and model (Catalog snapshots)
     * @param sud            sustained use discount inputs per series
     * @param commitments    unused resource CUD coverage per scope
     */
    public record Gcp(String billingSource, String sharedCoreTable, BigDecimal declaredDiscountPct,
                      List<Shape> shapes, List<UnitPrice> prices, List<Sud> sud, List<Cud> commitments) {
        public Gcp {
            shapes = copy(shapes);
            prices = copy(prices);
            sud = copy(sud);
            commitments = copy(commitments);
        }
    }

    /**
     * One machine type. {@code vcpu} is the billed vCPU (the {@code gcp-shared-core-v1} fraction for a shared-core
     * type, never {@code guestCpus}); null means a shared-core type missing from the table
     * ({@code GCP_SHARED_CORE_UNSUPPORTED}). {@code gib} is null when memory could not be read.
     */
    public record Shape(String instanceType, String series, BigDecimal vcpu, BigDecimal gib, boolean sharedCore) {}

    /** The core or RAM unit price of a series and model, from one Catalog snapshot. {@code resource} is CORE or RAM. */
    public record UnitPrice(String snapshotId, String model, String series, String resource, BigDecimal rate,
                            Instant effectiveFrom, Instant effectiveTo) {}

    /**
     * Sustained use discount inputs of one series for the statement month (design-ledger.md §3.9):
     * {@code maxRate} (the series maximum from the versioned config, 0 when the series earns none),
     * {@code tiers} (the price multiplier of each quarter of the month, in order), the export's
     * {@code observedRate} (credits over eligible cost, null without an export), {@code monthHours}, the
     * counterfactual's usage {@code cf} and the customer's own On-Demand usage per candidate pooling scope.
     * {@code snapshotId} is the stamped {@code SUD_RATE} snapshot.
     */
    public record Sud(String series, BigDecimal maxRate, List<BigDecimal> tiers, BigDecimal observedRate,
                      Integer monthHours, List<Span> cf, List<Scope> scopes, String snapshotId) {
        public Sud {
            tiers = copy(tiers);
            cf = copy(cf);
            scopes = copy(scopes);
        }
    }

    /** A candidate SUD pooling scope (region and series, or wider, V-LED-11) and the customer's own usage in it. */
    public record Scope(String name, List<Span> own) {
        public Scope {
            own = copy(own);
        }
    }

    /** Usage of {@code vcpu} and {@code gib} for every hour in {@code [fromHour, toHour)} of the month. */
    public record Span(int fromHour, int toHour, BigDecimal vcpu, BigDecimal gib) {}

    /**
     * Unused resource CUD coverage of one scope (series and region) and day (design-ledger.md §5.3, §5.4):
     * unused vCPU-hours and GiB-hours, the other groups' coverable hours in the scope, and, for the last group by
     * {@code group_id}, what was already allocated to the others. {@code RATE_UNKNOWN} and {@code UNAVAILABLE} cap
     * the tier at MEASURED_ONLY.
     */
    public record Cud(String scopeId, String series, CommitmentData data, BigDecimal unusedVcpuHours,
                      BigDecimal unusedGibHours, BigDecimal otherVcpuHours, BigDecimal otherGibHours,
                      boolean lastGroup, BigDecimal allocatedVcpuToOthers, BigDecimal allocatedGibToOthers) {}
}
