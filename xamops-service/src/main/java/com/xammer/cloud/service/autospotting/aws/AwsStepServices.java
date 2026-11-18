package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * The collaborators the AWS conversion steps share (design-aws.md §2.1), bundled so a step takes one
 * argument. The only way a step reaches the write-mode client is {@link #mutating}, which first
 * re-checks {@link AwsProviderGate}: a second lock behind the confirm path's switch (p).
 */
@Component
public class AwsStepServices {

    private final CloudAccountRepository accounts;
    private final AwsViewSource views;
    private final AwsMutatingSource mutatingSource;
    private final AwsProviderGate gate;
    private final AwsEligibilityEvaluator evaluator;
    private final AwsPlanReader reader;
    private final AwsGroupStatusReader statusReader;
    private final AwsSnapshotStore snapshots;
    private final OperationFacts facts;
    private final AuditRedactor redactor;
    private final AwsRolloutObserver observer;
    private final AwsRolloutEvaluator rollout;
    private final AwsLaunchEventRecorder events;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AwsStepServices(CloudAccountRepository accounts, AwsViewSource views, AwsMutatingSource mutatingSource,
                           AwsProviderGate gate, AwsEligibilityEvaluator evaluator, AwsPlanReader reader,
                           AwsGroupStatusReader statusReader, AwsSnapshotStore snapshots, OperationFacts facts,
                           AuditRedactor redactor, AwsRolloutObserver observer, AwsRolloutEvaluator rollout,
                           AwsLaunchEventRecorder events) {
        this(accounts, views, mutatingSource, gate, evaluator, reader, statusReader, snapshots, facts, redactor,
                observer, rollout, events, Clock.systemUTC());
    }

    /** Test seam: an explicit clock. Not a Spring constructor (the public one is the only wired one). */
    AwsStepServices(CloudAccountRepository accounts, AwsViewSource views, AwsMutatingSource mutatingSource,
                    AwsProviderGate gate, AwsEligibilityEvaluator evaluator, AwsPlanReader reader,
                    AwsGroupStatusReader statusReader, AwsSnapshotStore snapshots, OperationFacts facts,
                    AuditRedactor redactor, AwsRolloutObserver observer, AwsRolloutEvaluator rollout,
                    AwsLaunchEventRecorder events, Clock clock) {
        this.accounts = accounts;
        this.views = views;
        this.mutatingSource = mutatingSource;
        this.gate = gate;
        this.evaluator = evaluator;
        this.reader = reader;
        this.statusReader = statusReader;
        this.snapshots = snapshots;
        this.facts = facts;
        this.redactor = redactor;
        this.observer = observer;
        this.rollout = rollout;
        this.events = events;
        this.clock = clock;
    }

    public Optional<CloudAccount> account(Long id) {
        return id == null ? Optional.empty() : accounts.findById(id);
    }

    public AwsReadOnlyView view(CloudAccount account, String region) {
        return views.view(account, region);
    }

    /** The write-mode client; refuses unless every AWS mutation switch is on. */
    public AwsMutatingClient mutating(CloudAccount account, String region) {
        gate.requireOpen();
        return mutatingSource.mutatingClient(account, region);
    }

    public AwsEligibilityEvaluator evaluator() {
        return evaluator;
    }

    public AwsPlanReader reader() {
        return reader;
    }

    public AwsGroupStatusReader statusReader() {
        return statusReader;
    }

    public AwsSnapshotStore snapshots() {
        return snapshots;
    }

    public OperationFacts facts() {
        return facts;
    }

    public AuditRedactor redactor() {
        return redactor;
    }

    public AwsRolloutObserver observer() {
        return observer;
    }

    public AwsRolloutEvaluator rollout() {
        return rollout;
    }

    public AwsLaunchEventRecorder events() {
        return events;
    }

    public Clock clock() {
        return clock;
    }
}
