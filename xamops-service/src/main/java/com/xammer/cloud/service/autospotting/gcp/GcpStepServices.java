package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * The collaborators the GCP conversion steps share (design-gcp.md §2.6), bundled so a step takes one argument.
 * The only way a step reaches the write-mode client is {@link #mutating}: a conversion program re-checks
 * {@link GcpProviderGate} (switch (p)) on every call; the policy-only program (MIG_POLICY_UPDATE) is not a
 * conversion and asks for {@link #mutatingPolicyOnly}.
 */
@Component
public class GcpStepServices {
    private final CloudAccountRepository accounts;
    private final GcpViewSource views;
    private final GcpMutatingSource mutatingSource;
    private final GcpProviderGate gate;
    private final GcpEligibilityEvaluator evaluator;
    private final GcpMigStatusReader statusReader;
    private final GcpSnapshotStore snapshots;
    private final GcpTemplateRegistry registry;
    private final GcpVersionTargetEvaluator rollout;
    private final OperationFacts facts;
    private final Clock clock;
    private final Duration retention;

    @Autowired
    public GcpStepServices(CloudAccountRepository accounts, GcpViewSource views, GcpMutatingSource mutatingSource,
                           GcpProviderGate gate, GcpEligibilityEvaluator evaluator, GcpMigStatusReader statusReader,
                           GcpSnapshotStore snapshots, GcpTemplateRegistry registry, GcpVersionTargetEvaluator rollout,
                           OperationFacts facts,
                           @Value("${autospotting.gcp.template-retention:7d}") Duration retention) {
        this(accounts, views, mutatingSource, gate, evaluator, statusReader, snapshots, registry, rollout, facts,
                Clock.systemUTC(), retention);
    }

    /** Test seam: an explicit clock. Not a Spring constructor. */
    GcpStepServices(CloudAccountRepository accounts, GcpViewSource views, GcpMutatingSource mutatingSource,
                    GcpProviderGate gate, GcpEligibilityEvaluator evaluator, GcpMigStatusReader statusReader,
                    GcpSnapshotStore snapshots, GcpTemplateRegistry registry, GcpVersionTargetEvaluator rollout,
                    OperationFacts facts, Clock clock, Duration retention) {
        this.accounts = accounts;
        this.views = views;
        this.mutatingSource = mutatingSource;
        this.gate = gate;
        this.evaluator = evaluator;
        this.statusReader = statusReader;
        this.snapshots = snapshots;
        this.registry = registry;
        this.rollout = rollout;
        this.facts = facts;
        this.clock = clock;
        this.retention = retention;
    }

    public Optional<CloudAccount> account(Long id) {
        return id == null ? Optional.empty() : accounts.findById(id);
    }

    public GcpReadOnlyView view(CloudAccount account) {
        return views.view(account);
    }

    /** The write-mode client for a conversion; refuses unless every GCP mutation switch is on. */
    public GcpMigClient.Mutating mutating(CloudAccount account) {
        gate.requireOpen();
        return mutatingSource.mutating(account);
    }

    /** The write-mode client for MIG_POLICY_UPDATE, which is not a conversion (design-core.md §6.3). */
    public GcpMigClient.Mutating mutatingPolicyOnly(CloudAccount account) {
        return mutatingSource.mutating(account);
    }

    public GcpEligibilityEvaluator evaluator() {
        return evaluator;
    }

    public GcpMigStatusReader statusReader() {
        return statusReader;
    }

    public GcpSnapshotStore snapshots() {
        return snapshots;
    }

    public GcpTemplateRegistry registry() {
        return registry;
    }

    public GcpVersionTargetEvaluator rollout() {
        return rollout;
    }

    public OperationFacts facts() {
        return facts;
    }

    public Clock clock() {
        return clock;
    }

    public Duration retention() {
        return retention;
    }
}
