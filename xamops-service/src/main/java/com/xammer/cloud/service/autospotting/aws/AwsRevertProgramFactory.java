package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepProgram;

/**
 * The seam between the Phase 18 conversion programs and the Phase 19 REVERT program (design-aws.md
 * §5.3). An ENABLE that must compensate restores its snapshot through the REVERT program, and a REVERT
 * operation itself is that program. Phase 18 ships only {@link StubAwsRevertProgramFactory}, a program that fails
 * safely without an AWS call; Phase 19 replaces it with the real program and deletes the stub.
 */
public interface AwsRevertProgramFactory {

    /** The program that restores {@code snapshot}: the REVERT op, and the SNAPSHOT compensation leg. */
    StepProgram program(OperationContext op, SnapshotPayload snapshot);
}
