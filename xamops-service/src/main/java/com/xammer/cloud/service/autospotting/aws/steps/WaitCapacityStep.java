package com.xammer.cloud.service.autospotting.aws.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutObserver;
import com.xammer.cloud.service.autospotting.aws.AwsRolloutProperties;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Instant;
import java.util.Optional;

/**
 * The tail wait of FALLBACK_ENTER (design-aws.md §6.4 step 4, §6.5): done when {@code healthyInService >=
 * desired} (AC-HLT-01), bounded by {@code autospotting.aws.fallback.enter-wait} (30 min). Remaining Spot
 * instances are replaced by On-Demand launch-first, which is acceptable: those pools are under stress.
 * Read-only. Each poll reports {@code capacity=OK|SHORT} and, once the group has been short for the
 * still-short window (20 min) since this wait began, {@code degrade=true}; the runner applies FALLBACK to
 * DEGRADED and back, so the provider never sets a state. At the bound the step ends with outcome
 * {@code CAPACITY_PENDING} and the reconciler's FALLBACK and DEGRADED rules take over; there is nothing to
 * restore on AWS. A revert preempts the op at any boundary.
 *
 * <p>Also the second step of a FALLBACK_EXIT's compensation (back to 100% On-Demand), where the runner applies
 * no state effect.
 */
public final class WaitCapacityStep extends AwsStep {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FallbackProperties props;

    public WaitCapacityStep(AwsStepServices services, AwsProgram program, FallbackProperties props) {
        super(services, program);
        this.props = props;
    }

    @Override
    public String name() {
        return FallbackSteps.WAIT_CAPACITY;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
        }
        AwsReadOnlyView view = services.view(account, program.region());
        Optional<AwsRolloutObserver.Snapshot> snap = services.observer().observe(view, program.groupName());
        if (snap.isEmpty()) {
            return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The Auto Scaling group no longer exists.");
        }
        Instant now = services.clock().instant();
        JsonNode prior = prior();
        Instant startedAt = instant(prior, "startedAt");
        if (startedAt == null) {
            startedAt = now;
        }
        boolean ok = snap.get().healthyInService() >= snap.get().desired();
        Instant shortSince = ok ? null : (instant(prior, "shortSince") == null ? now : instant(prior, "shortSince"));
        boolean degrade = !ok && !startedAt.plus(props.stillShort()).isAfter(now);
        boolean bound = !startedAt.plus(props.enterWait()).isAfter(now);
        ObjectNode out = JSON.createObjectNode();
        out.put("startedAt", startedAt.toString());
        out.put(FallbackSteps.CAPACITY, ok ? FallbackSteps.OK : FallbackSteps.SHORT);
        out.put(FallbackSteps.SHORT_SINCE, shortSince == null ? null : shortSince.toString());
        out.put("degrade", degrade);
        out.put("healthyInService", snap.get().healthyInService());
        out.put("desired", snap.get().desired());
        if (ok) {
            return done(out.toString());
        }
        if (bound) {
            out.put("outcome", "CAPACITY_PENDING");
            return done(out.toString());
        }
        return new StepOutcome.InProgress(AwsRolloutProperties.POLL, out.toString());
    }

    private JsonNode prior() {
        String json = services.facts().operationProgress(program.operationId()).orElse(null);
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static Instant instant(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank() || "null".equals(v)) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // every poll is a fresh read; execute is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Watch the group every minute until every desired instance is healthy, for up to "
                + props.enterWait().toMinutes() + " minutes. Nothing is changed.";
    }
}
