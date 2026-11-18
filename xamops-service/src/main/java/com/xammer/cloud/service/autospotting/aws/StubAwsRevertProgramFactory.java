package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stands in for the Phase 19 REVERT program until it exists. It builds a one-step program whose only
 * step fails at once without any AWS call, so a compensating ENABLE (or a REVERT) ends
 * {@code FAILED} with {@code REVERT_PROGRAM_NOT_AVAILABLE} for an operator, instead of throwing inside
 * the runner or guessing a restore. No ENABLE can start while the mutation switches are off, so this
 * path is unreachable until Phase 23, and Phase 19 replaces this class with the real program.
 */
@Component
public class StubAwsRevertProgramFactory implements AwsRevertProgramFactory {

    public static final String CODE = "REVERT_PROGRAM_NOT_AVAILABLE";

    @Override
    public StepProgram program(OperationContext op, SnapshotPayload snapshot) {
        return new StepProgram(List.of(new NotAvailableStep()));
    }

    /** The single failing step. Read-only: it never mutates and never calls AWS. */
    static final class NotAvailableStep implements Step {

        @Override
        public String name() {
            return "REVERT_NOT_AVAILABLE";
        }

        @Override
        public StepOutcome execute(StepContext ctx) {
            return new StepOutcome.Failed(ErrorKind.VALIDATION, CODE,
                    "The restore program is not available yet. An operator must restore the saved configuration.");
        }

        @Override
        public StepOutcome probe(StepContext ctx) {
            return new StepOutcome.InProgress(java.time.Duration.ZERO, null);
        }

        @Override
        public String describeIntent(StepContext ctx) {
            return "Stop: the restore program is not available yet.";
        }
    }
}
