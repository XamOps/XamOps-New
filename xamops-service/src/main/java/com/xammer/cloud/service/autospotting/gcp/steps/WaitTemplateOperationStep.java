package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpErrorMapper;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateCloner;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateRegistry;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Optional;

/**
 * Step 3 (design-gcp.md §2.6): wait for the template insert Operation, then read the template back. Done only
 * when the template exists with the registered body (its numeric id is recorded and the registry row goes
 * IN_USE). An Operation that is DONE with an error fails with its mapped kind. Read-only.
 */
public final class WaitTemplateOperationStep extends GcpStep {
    public static final String NAME = "WAIT_TEMPLATE_OPERATION";
    static final Duration POLL = Duration.ofSeconds(5);

    public WaitTemplateOperationStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        Optional<GcpTemplateRegistry.Row> found = services.registry().findByOp(program.operationId());
        CloudAccount account = account().orElse(null);
        if (found.isEmpty() || account == null) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NOT_REGISTERED", "The Spot template was not registered.");
        }
        GcpTemplateRegistry.Row row = found.get();
        GcpReadOnlyView view = services.view(account);
        String[] ref = GcpTargets.operationRef(row.insertOperation());
        if (ref != null && "CREATING".equals(row.status())) {
            JsonNode op = view.reads().getOperation(ref[0], ref[1], ref[2]);
            GcpErrorMapper.OperationOutcome out = GcpErrorMapper.operationOutcome(op);
            if (!out.done()) {
                return new StepOutcome.InProgress(POLL, null);
            }
            if (out.failed()) {
                return failed(out.errorKind(), "GCP_TEMPLATE_INSERT_FAILED", "Creating the Spot template failed ("
                        + out.errorCode() + ").");
            }
        }
        Optional<JsonNode> cloud = tryGet(view, row.selfLink());
        if (cloud.isEmpty()) {
            return new StepOutcome.InProgress(POLL, null);
        }
        if (!GcpTemplateCloner.isOurs(cloud.get(), row.bodySha256(), program.groupId())) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_BODY_MISMATCH", "The created template does not match the plan.");
        }
        services.registry().markInUse(row.id(), cloud.get().path("id").asText(null), services.clock().instant());
        return done("{\"template\":\"" + row.selfLink() + "\",\"id\":\"" + cloud.get().path("id").asText("") + "\"}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        return notYet(); // read-only: execute is the probe
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Wait for the Spot template to exist and check it matches the plan.";
    }
}
