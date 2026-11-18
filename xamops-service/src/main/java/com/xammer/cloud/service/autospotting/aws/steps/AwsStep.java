package com.xammer.cloud.service.autospotting.aws.steps;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsProgram;
import com.xammer.cloud.service.autospotting.aws.AwsReadException;
import com.xammer.cloud.service.autospotting.aws.AwsStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.Optional;
import java.util.Set;

/**
 * The shared shell of an AWS conversion step (design-aws.md §2.3): the program, the services, and
 * one place that turns an AWS failure into a {@link StepOutcome.Failed} with a stable code and a
 * message that carries no provider text (FR-SEC-06). A step overrides {@link #doExecute} and
 * {@link #doProbe}; a {@code LeaseLostException} is never caught here, so the runner sees it.
 */
abstract class AwsStep implements Step {

    /** AWS error codes that mean another change is in flight: wait, never compensate. */
    private static final Set<String> IN_FLIGHT = Set.of("ScalingActivityInProgress", "InstanceRefreshInProgress",
            "ResourceInUse", "ResourceContention");

    protected final AwsStepServices services;
    protected final AwsProgram program;

    AwsStep(AwsStepServices services, AwsProgram program) {
        this.services = services;
        this.program = program;
    }

    @Override
    public final StepOutcome execute(StepContext ctx) {
        try {
            return doExecute(ctx);
        } catch (AwsReadException e) {
            return new StepOutcome.Failed(e.kind(), "AWS_" + e.check().toUpperCase(java.util.Locale.ROOT),
                    "AWS " + e.check() + " failed.");
        } catch (SdkException e) {
            return fail(e);
        }
    }

    @Override
    public final StepOutcome probe(StepContext ctx) {
        try {
            return doProbe(ctx);
        } catch (AwsReadException | SdkException e) {
            // A failed probe proves nothing; the runner re-issues execute with the same token.
            return new StepOutcome.InProgress(java.time.Duration.ZERO, null);
        }
    }

    abstract StepOutcome doExecute(StepContext ctx);

    /** Read-only: has this step's effect already happened? Done if so, otherwise a non-Done outcome. */
    abstract StepOutcome doProbe(StepContext ctx);

    static StepOutcome notYet() {
        return new StepOutcome.InProgress(java.time.Duration.ZERO, null);
    }

    static StepOutcome.Failed failed(ErrorKind kind, String code, String message) {
        return new StepOutcome.Failed(kind, code, message);
    }

    /**
     * Map an SDK failure to a kind and a code. Only the AWS error code is kept, never its message, and it
     * goes through the redactor like every provider string that is stored or shown (FR-SEC-06).
     */
    StepOutcome.Failed fail(SdkException e) {
        String code = e instanceof AwsServiceException a && a.awsErrorDetails() != null
                ? services.redactor().redactMessage(a.awsErrorDetails().errorCode()) : null;
        ErrorKind kind = code != null && IN_FLIGHT.contains(code) ? ErrorKind.CONFLICT : AwsErrors.classify(e);
        String stable = code == null ? "AWS_CALL_FAILED" : "AWS_" + code.toUpperCase(java.util.Locale.ROOT);
        return new StepOutcome.Failed(kind, stable, "AWS rejected or could not complete the request"
                + (code == null ? "." : " (" + code + ")."));
    }

    protected Optional<CloudAccount> account() {
        return services.account(program.cloudAccountId());
    }

    static StepOutcome.Done done(String json) {
        return new StepOutcome.Done(json, java.util.List.of());
    }
}
