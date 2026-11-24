package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpFingerprint;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 3 of FALLBACK_ENTER (design-gcp.md §7.3): a MIG {@code get} shows the single {@code xas-standard} version.
 * Done carries the live hash {@code getStatus} reports as {@code fallbackFingerprint} (the RESTORE_UPDATE_POLICY step
 * refreshes it once the policy, the last thing a fallback changes, is final); the runner then moves the group to FALLBACK
 * (or PAUSED with {@code paused_from_state=FALLBACK}) in the same fenced transaction, with {@code effective_at} equal
 * to the PATCH's {@code intent_at} so billing ends at the mutation. The group is in FALLBACK whether or not standard
 * capacity is there yet. Read-only.
 */
public final class VerifyFallbackAppliedStep extends GcpStep {
    public VerifyFallbackAppliedStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return FallbackSteps.VERIFY_FALLBACK_APPLIED;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return accountMissing();
        }
        Optional<GcpMigFacts> live = live(services.view(account));
        if (live.isEmpty()) {
            return groupMissing();
        }
        ArrayNode versions = targetVersions(live.get());
        if (versions == null) {
            return failed(ErrorKind.VALIDATION, "ORIGINAL_TEMPLATE_MISSING", "The original template could not be determined.");
        }
        if (!GcpTargets.matches(live.get(), versions)) {
            return new StepOutcome.InProgress(Duration.ofSeconds(5), null);
        }
        return done("{\"" + FallbackSteps.FINGERPRINT + "\":\"" + GcpFingerprint.planHash(live.get()) + "\"}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: execute is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Check that the instance group now runs only your original template. Nothing is changed.";
    }
}
