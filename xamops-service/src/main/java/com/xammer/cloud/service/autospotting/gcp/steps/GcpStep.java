package com.xammer.cloud.service.autospotting.gcp.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.gcp.GcpMigFacts;
import com.xammer.cloud.service.autospotting.gcp.GcpRevertTarget;
import com.xammer.cloud.service.autospotting.gcp.GcpTargets;
import com.xammer.cloud.service.autospotting.gcp.GcpProgram;
import com.xammer.cloud.service.autospotting.gcp.GcpReadException;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpStepServices;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The shared shell of a GCP conversion step (design-gcp.md §2.6): the program, the services, and one place that
 * turns a GCP failure into a {@link StepOutcome.Failed} with a stable code and a message that carries no
 * provider text (FR-SEC-06). A step overrides {@link #doExecute} and {@link #doProbe}. A
 * {@code LeaseLostException} is never caught here, so the runner sees it.
 */
abstract class GcpStep implements Step {
    protected final GcpStepServices services;
    protected final GcpProgram program;

    GcpStep(GcpStepServices services, GcpProgram program) {
        this.services = services;
        this.program = program;
    }

    @Override
    public final StepOutcome execute(StepContext ctx) {
        try {
            return doExecute(ctx);
        } catch (GcpReadException e) {
            return fromException(e);
        }
    }

    @Override
    public final StepOutcome probe(StepContext ctx) {
        try {
            return doProbe(ctx);
        } catch (GcpReadException e) {
            // A failed probe proves nothing; the runner re-issues execute with the same token.
            return notYet();
        }
    }

    abstract StepOutcome doExecute(StepContext ctx);

    /** Read-only: has this step's effect already happened? Done if so, otherwise a non-Done outcome. */
    abstract StepOutcome doProbe(StepContext ctx);

    static StepOutcome notYet() {
        return new StepOutcome.InProgress(Duration.ZERO, null);
    }

    static StepOutcome.Failed failed(ErrorKind kind, String code, String message) {
        return new StepOutcome.Failed(kind, code, message);
    }

    static StepOutcome.Done done(String json) {
        return new StepOutcome.Done(json, java.util.List.of());
    }

    /** A 409, 412 or resourceNotReady is a conflict, never success (AC-GCP-03). */
    static StepOutcome.Failed fromException(GcpReadException e) {
        String code = e.kind() == ErrorKind.CONFLICT && (e.check().startsWith("instanceGroupManagers.patch")
                || e.check().startsWith("instanceGroupManagers.update"))
                ? "GCP_MIG_UPDATE_CONFLICT" : "GCP_" + e.check().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return new StepOutcome.Failed(e.kind(), code, "GCP " + e.check() + " failed"
                + (e.httpStatus() > 0 ? " (HTTP " + e.httpStatus() + ")." : "."));
    }

    protected Optional<CloudAccount> account() {
        return services.account(program.cloudAccountId());
    }

    protected Optional<GcpMigFacts> live(GcpReadOnlyView view) {
        Optional<JsonNode> raw = services.statusReader().describeGroup(view, program.key());
        return raw.map(r -> GcpMigFacts.from(program.key().project(), r));
    }

    protected static UUID requestId(StepContext ctx) {
        return UUID.fromString(ctx.idempotencyToken());
    }

    protected static StepOutcome.Failed accountMissing() {
        return failed(ErrorKind.VALIDATION, "ACCOUNT_MISSING", "The connected account could not be found.");
    }

    protected static StepOutcome.Failed groupMissing() {
        return failed(ErrorKind.NOT_FOUND, "GROUP_NOT_FOUND", "The instance group no longer exists.");
    }

    /** The customer's original template: the live standard version, else the saved snapshot, else the live MIG. */
    protected String originalTemplate(GcpMigFacts g) {
        for (GcpMigFacts.Version v : g.versions()) {
            if (GcpMigFacts.STANDARD_VERSION.equals(v.name()) && v.instanceTemplate() != null) {
                return v.instanceTemplate();
            }
        }
        Optional<com.xammer.cloud.service.autospotting.gcp.GcpSnapshotStore.Stored> snap =
                services.snapshots().findByOp(program.operationId());
        if (snap.isEmpty()) {
            snap = services.snapshots().findLinked(program.groupId());
        }
        if (snap.isPresent()) {
            try {
                JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snap.get().payload());
                String t = n.path("instanceTemplate").asText(null);
                if (t == null || t.isBlank()) {
                    t = n.path("versions").path(0).path("instanceTemplate").asText(null);
                }
                if (t != null && !t.isBlank()) {
                    return t;
                }
            } catch (java.io.IOException ignored) {
                // fall through to the live MIG
            }
        }
        if (g.instanceTemplate() != null) {
            return g.instanceTemplate();
        }
        return g.versions().isEmpty() ? null : g.versions().get(0).instanceTemplate();
    }

    /** True when the §4.4 success test passes against this program's target right now (used where a wait may have been cut short). */
    protected boolean rolloutComplete(GcpReadOnlyView view, GcpMigFacts g) {
        com.fasterxml.jackson.databind.node.ArrayNode versions = targetVersions(g);
        if (versions == null) {
            return false;
        }
        java.time.Instant now = services.clock().instant();
        var managed = view.reads().listManagedInstances(g.regional(), g.location(), g.name());
        var o = com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Observation.of(now, g, managed,
                java.util.List.of());
        var target = new com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Target(GcpTargets.expected(versions),
                GcpTargets.floorOf(versions));
        var asStable = new com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.State(now, now, -1,
                now.minus(com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.STABLE_GAP));
        return services.rollout().evaluate(o, target, asStable,
                com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Mode.LENIENT)
                instanceof com.xammer.cloud.service.autospotting.gcp.GcpVersionTargetEvaluator.Done;
    }

    /** The snapshot {@code instanceFlexibilityPolicy} a REVERT restores, or null (never for another kind). */
    protected JsonNode revertFlexibility(GcpMigFacts g) {
        if (program.kind() != OperationKind.REVERT) {
            return null;
        }
        CloudAccount account = account().orElse(null);
        if (account == null) {
            return null;
        }
        return GcpRevertTarget.resolve(services, program, services.view(account), g).flexibility();
    }

    /** The region of a regional template self-link, or null for a global one. */
    protected static String regionOf(String templateLink) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/regions/([^/]+)/instanceTemplates/").matcher(templateLink);
        return m.find() ? m.group(1) : null;
    }

    protected static String templateSelfLink(String project, String region, String name) {
        return "https://www.googleapis.com/compute/v1/projects/" + project + (region == null ? "/global" : "/regions/" + region)
                + "/instanceTemplates/" + name;
    }

    protected static Optional<JsonNode> tryGet(GcpReadOnlyView view, String selfLink) {
        try {
            return Optional.of(view.reads().getInstanceTemplate(selfLink));
        } catch (GcpReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The {@code versions[]} this program drives the MIG to; null when the Spot template is not registered yet. */
    protected com.fasterxml.jackson.databind.node.ArrayNode targetVersions(GcpMigFacts g) {
        OperationKind kind = program.kind();
        if (kind == OperationKind.REVERT) {
            CloudAccount account = account().orElse(null);
            if (account == null) {
                return null;
            }
            GcpRevertTarget.Resolved r = GcpRevertTarget.resolve(services, program, services.view(account), g);
            return r.failure() == null ? r.versions() : null;
        }
        if (kind == OperationKind.FALLBACK_ENTER || (kind == OperationKind.FALLBACK_EXIT && program.compensation())) {
            String original = originalTemplate(g);
            return original == null ? null : GcpTargets.fallbackVersions(original);
        }
        if (kind == OperationKind.FALLBACK_EXIT) {
            String original = originalTemplate(g);
            return services.registry().latestSpot(program.groupId()).filter(r -> original != null)
                    .map(r -> GcpTargets.versions(program, original, r.selfLink())).orElse(null);
        }
        if (program.compensation()) {
            return com.xammer.cloud.service.autospotting.gcp.GcpTargets.versions(program, null, null);
        }
        return services.registry().findByOp(program.operationId())
                .map(r -> com.xammer.cloud.service.autospotting.gcp.GcpTargets.versions(program, originalTemplate(g),
                        r.selfLink()))
                .orElse(null);
    }

    /**
     * PATCH {@code body} with the raw fingerprint as the precondition. A 412 means "re-read and compare": when the
     * plan hash is unchanged the change was non-material (an autoscaler resize), so the PATCH is re-sent once with the
     * new fingerprint; otherwise, and for any 409, the {@link GcpReadException} (CONFLICT) propagates and is never
     * recorded as success.
     */
    protected JsonNode patch(StepContext ctx, GcpReadOnlyView view, com.xammer.cloud.service.autospotting.gcp.GcpMigClient.Mutating client,
                             GcpMigFacts first, JsonNode body) {
        GcpMigFacts cur = first;
        for (int attempt = 0; ; attempt++) {
            ctx.leaseHandle().assertHeld(ctx.callTimeout());
            UUID id = attempt == 0 ? requestId(ctx)
                    : UUID.nameUUIDFromBytes((ctx.idempotencyToken() + "-r" + attempt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                return client.patchMig(cur.regional(), cur.location(), cur.name(), body, cur.rawFingerprint(), id);
            } catch (GcpReadException e) {
                if (e.httpStatus() != 412 || attempt >= 1) {
                    throw e;
                }
                GcpMigFacts reread = live(view).orElseThrow(() -> e);
                if (!com.xammer.cloud.service.autospotting.gcp.GcpFingerprint.planHash(reread)
                        .equals(com.xammer.cloud.service.autospotting.gcp.GcpFingerprint.planHash(first))) {
                    throw e;
                }
                cur = reread;
            }
        }
    }
}
