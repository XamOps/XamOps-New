package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepProgram;

/**
 * The revert interface of the AWS provider (design-aws.md §5.3). A REVERT operation and the SNAPSHOT
 * compensation leg of an ENABLE both restore the stored snapshot through the one program; {@link AwsRestoreProgram}
 * is the implementation. The provider reaches it only through {@link AwsProviderGate}.
 */
public interface AwsRevertProgramFactory {
    /** The program that restores the stored snapshot: the REVERT op, and the SNAPSHOT compensation leg. */
    StepProgram program(OperationContext op, SnapshotPayload snapshot);

    /**
     * The REVERT plan, read-only: the three-way merge, the permission pre-check and, when an exact restore is
     * impossible, the closest safe target marked {@code exact=false}. Never runs the conversion eligibility checks.
     */
    PlanBody plan(PlanRequest request, CloudAccount account);

    /**
     * True when the group's linked original snapshot exists and decodes (D-23b-03 review #8). The
     * CONFIG_STILL_ON_GROUP text recommends Revert only then. No default (review #6): every implementation answers.
     */
    boolean originalReadable(java.util.UUID groupId);
}
