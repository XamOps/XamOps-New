package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigClient;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateCloner;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateRegistry;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * Step 2 (design-gcp.md §2.6): insert the Spot copy of the original template (deep copy, only the scheduling
 * fields change, {@link GcpTemplateCloner}). The registry row is written {@code CREATING} BEFORE the insert call,
 * so a crash between call and result still leaves a record and the template is deletable later through the
 * registry only. The insert carries {@code requestId} = the step token. A probe reads the template by its
 * deterministic name: the same sha256 adopts it (idempotent), a different body is a VALIDATION failure, never
 * overwritten.
 */
public final class CreateSpotTemplateStep extends GcpStep {
    public static final String NAME = "CREATE_SPOT_TEMPLATE";

    public CreateSpotTemplateStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean mutates() {
        return true;
    }

    @Override
    StepOutcome doExecute(StepContext ctx) {
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return accountMissing();
        }
        GcpReadOnlyView view = services.view(account);
        Optional<GcpMigFacts> live = live(view);
        if (live.isEmpty()) {
            return groupMissing();
        }
        String original = originalTemplate(live.get());
        if (original == null) {
            return failed(ErrorKind.VALIDATION, "ORIGINAL_TEMPLATE_MISSING", "The original template could not be determined.");
        }
        String project = program.key().project();
        String region = regionOf(original);
        String scope = region == null ? "global" : "regions/" + region;
        String name = GcpTemplateCloner.nameFor(program.key().name(), program.operationId());
        String selfLink = templateSelfLink(project, region, name);
        JsonNode source = view.reads().getInstanceTemplate(original);
        String sourceName = original.substring(original.lastIndexOf('/') + 1);
        GcpTemplateCloner.Clone clone = GcpTemplateCloner.clone(source, sourceName, program.groupId(),
                program.key().name(), program.operationId());
        int generation = services.registry().maxGeneration(program.groupId()) + 1;
        GcpTemplateRegistry.Row row = services.registry().registerCreating(program.groupId(), generation, "SPOT", name,
                scope, selfLink, original, source.path("id").asText(null), clone.sha256(), program.operationId(),
                services.clock().instant());
        if (!row.bodySha256().equals(clone.sha256())) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_BODY_CHANGED",
                    "The original template changed while the copy was being made. Plan again.");
        }
        Optional<JsonNode> existing = tryGet(view, selfLink);
        if (existing.isPresent()) {
            return adopt(row, existing.get());
        }
        ctx.leaseHandle().assertHeld(ctx.callTimeout());
        GcpMigClient.Mutating client = services.mutating(account);
        try {
            JsonNode op = client.insertInstanceTemplate(region, clone.body(), requestId(ctx));
            services.registry().setInsertOperation(row.id(), op.path("selfLink").asText(null), services.clock().instant());
        } catch (GcpReadException e) {
            if (e.httpStatus() == 409) {
                // Past the requestId window a duplicate insert is 409 alreadyExists: resolve it by content.
                Optional<JsonNode> now = tryGet(view, selfLink);
                if (now.isPresent()) {
                    return adopt(row, now.get());
                }
            }
            throw e;
        }
        return done("{\"template\":\"" + selfLink + "\",\"sha256\":\"" + clone.sha256() + "\"}");
    }

    private StepOutcome adopt(GcpTemplateRegistry.Row row, JsonNode cloud) {
        if (!GcpTemplateCloner.isOurs(cloud, row.bodySha256(), program.groupId())) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NAME_TAKEN",
                    "A template with this name already exists with different content. Nothing was changed.");
        }
        services.registry().markInUse(row.id(), cloud.path("id").asText(null), services.clock().instant());
        return done("{\"template\":\"" + row.selfLink() + "\",\"adopted\":true}");
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        Optional<GcpTemplateRegistry.Row> row = services.registry().findByOp(program.operationId());
        if (row.isEmpty()) {
            return notYet();
        }
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return notYet();
        }
        Optional<JsonNode> cloud = tryGet(services.view(account), row.get().selfLink());
        return cloud.isPresent() && GcpTemplateCloner.isOurs(cloud.get(), row.get().bodySha256(), program.groupId())
                ? done("{\"template\":\"" + row.get().selfLink() + "\",\"adopted\":true}") : notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Create a copy of your instance template that runs as Spot. Your original template is not changed or deleted.";
    }
}
