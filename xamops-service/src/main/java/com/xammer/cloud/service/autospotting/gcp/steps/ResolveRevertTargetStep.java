package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.gcp.GcpMigClient;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpRevertTarget;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateCloner;
import com.xammer.cloud.service.autospotting.gcp.GcpTemplateRegistry;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.util.Optional;

/**
 * {@code RESOLVE_REVERT_TARGET}, the revert's replacement for CREATE_SPOT_TEMPLATE (design-gcp.md §2.6). The
 * original template exists with the saved id: nothing to create ({@code exact=true}). It was deleted: insert a
 * template rebuilt from the captured JSON as {@code <migPrefix>-xas-<g8>-r<gen>}, written to the registry
 * {@code CREATING} first (role {@code REBUILT}, XamOps-owned, so it is retired and collected like any template of
 * ours), {@code exact=false}. A customer-changed or accepted standard template is kept by the three-way check.
 *
 * <p>{@link #mutates()} is true only when this run has to insert, because the REVERTING transition comes with the
 * first mutating step's intent: the rebuild when the original is gone, else the PATCH. A read that fails while
 * deciding counts as mutating, the safe side for the group's state.
 */
public final class ResolveRevertTargetStep extends GcpStep {
    public static final String NAME = "RESOLVE_REVERT_TARGET";

    public ResolveRevertTargetStep(GcpStepServices services, GcpProgram program) {
        super(services, program);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean mutates() {
        try {
            CloudAccount account = account().orElse(null);
            if (account == null) {
                return false;
            }
            GcpReadOnlyView view = services.view(account);
            Optional<GcpMigFacts> live = live(view);
            return live.isPresent() && GcpRevertTarget.resolve(services, program, view, live.get()).rebuild();
        } catch (RuntimeException e) {
            return true;
        }
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
        GcpRevertTarget.Resolved r = GcpRevertTarget.resolve(services, program, view, live.get());
        if (r.failure() != null) {
            return failed(ErrorKind.VALIDATION, r.failure(), "The original configuration cannot be restored from what was saved.");
        }
        if (!r.rebuild()) {
            return done("{\"exact\":" + r.exact() + ",\"rebuilt\":false,\"notes\":" + notes(r) + "}");
        }
        JsonNode resource = GcpRevertTarget.templateResource(program.target(), r.rebuildSource());
        String sourceName = r.rebuildSource().substring(r.rebuildSource().lastIndexOf('/') + 1);
        GcpTemplateCloner.Clone clone = GcpRevertTarget.rebuild(resource, sourceName, r.rebuildName(), program.groupId());
        String region = regionOf(r.rebuildSource());
        String scope = region == null ? "global" : "regions/" + region;
        int generation = services.registry().maxGeneration(program.groupId()) + 1;
        GcpTemplateRegistry.Row row = services.registry().registerCreating(program.groupId(), generation, "REBUILT",
                r.rebuildName(), scope, r.rebuiltLink(), r.rebuildSource(), null, clone.sha256(), program.operationId(),
                services.clock().instant());
        if (!row.bodySha256().equals(clone.sha256())) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NAME_TAKEN",
                    "A template with the rebuild's name already exists with different content. Nothing was changed.");
        }
        Optional<JsonNode> existing = tryGet(view, row.selfLink());
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
                Optional<JsonNode> now = tryGet(view, row.selfLink());
                if (now.isPresent()) {
                    return adopt(row, now.get());
                }
            }
            throw e;
        }
        return done("{\"exact\":false,\"rebuilt\":true,\"template\":\"" + row.selfLink() + "\",\"notes\":" + notes(r) + "}");
    }

    private StepOutcome adopt(GcpTemplateRegistry.Row row, JsonNode cloud) {
        if (!GcpRevertTarget.isRebuilt(cloud, row.bodySha256(), program.groupId())) {
            return failed(ErrorKind.VALIDATION, "TEMPLATE_NAME_TAKEN",
                    "A template with this name already exists with different content. Nothing was changed.");
        }
        services.registry().markInUse(row.id(), cloud.path("id").asText(null), services.clock().instant());
        return done("{\"exact\":false,\"rebuilt\":true,\"adopted\":true,\"template\":\"" + row.selfLink() + "\"}");
    }

    private static String notes(GcpRevertTarget.Resolved r) {
        return "[" + String.join(",", r.notes().stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }

    @Override
    StepOutcome doProbe(StepContext ctx) {
        Optional<GcpTemplateRegistry.Row> row = services.registry().findByOp(program.operationId());
        CloudAccount account = account().orElse(null);
        if (row.isEmpty() || account == null) {
            return notYet();
        }
        Optional<JsonNode> cloud = tryGet(services.view(account), row.get().selfLink());
        return cloud.isPresent() && GcpRevertTarget.isRebuilt(cloud.get(), row.get().bodySha256(), program.groupId())
                ? done("{\"rebuilt\":true,\"adopted\":true,\"template\":\"" + row.get().selfLink() + "\"}") : notYet();
    }

    @Override
    public String describeIntent(StepContext ctx) {
        return "Find the original instance template. If it was deleted, create a copy of it from the saved configuration. "
                + "A template you changed after enabling is kept.";
    }
}
