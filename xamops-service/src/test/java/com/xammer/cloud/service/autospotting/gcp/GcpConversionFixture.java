package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stubbed GCP for the conversion programs: an in-memory Compute (reads through {@link FakeGcp}, writes through
 * {@link FakeMutating}), an in-memory registry and snapshot store, and a driver that runs a program step by step.
 * No test using it calls GCP, and nothing here touches a database.
 */
final class GcpConversionFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "xamops-test/us-central1/web-mig";
    static final String ORIGINAL = FakeGcp.TEMPLATE;

    /** A clock the test advances. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** The fake Compute write facade. It changes the same MIG the reads see, like the real API. */
    static final class FakeMutating implements GcpMigClient.Mutating {
        final FakeGcp gcp;
        final List<String> calls = new ArrayList<>();
        final List<ObjectNode> patches = new ArrayList<>();
        final List<String> patchFingerprints = new ArrayList<>();
        final List<UUID> requestIds = new ArrayList<>();
        final List<ObjectNode> inserted = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        int patchStatusToFail;
        int patchFailuresLeft;
        int seq;

        FakeMutating(FakeGcp gcp) {
            this.gcp = gcp;
        }

        @Override
        public JsonNode insertInstanceTemplate(String regionOrNull, JsonNode body, UUID requestId) {
            calls.add("insert:" + (regionOrNull == null ? "global" : regionOrNull));
            requestIds.add(requestId);
            ObjectNode t = ((ObjectNode) body).deepCopy();
            String link = FakeGcp.P + (regionOrNull == null ? "/global" : "/regions/" + regionOrNull) + "/instanceTemplates/"
                    + body.path("name").asText();
            t.put("id", "9000" + (++seq));
            t.put("selfLink", link);
            gcp.templates.put(link, t);
            inserted.add(t);
            return JSON.createObjectNode().put("status", "DONE").put("selfLink", FakeGcp.P + "/global/operations/op-" + seq);
        }

        @Override
        public JsonNode deleteInstanceTemplate(String regionOrNull, String templateName, UUID requestId) {
            calls.add("delete:" + templateName);
            deleted.add(templateName);
            return JSON.createObjectNode().put("status", "DONE");
        }

        @Override
        public JsonNode patchMig(boolean regional, String location, String name, JsonNode body, String fingerprint, UUID requestId) {
            calls.add("patch");
            requestIds.add(requestId);
            patches.add(((ObjectNode) body).deepCopy());
            patchFingerprints.add(fingerprint);
            if (patchFailuresLeft > 0) {
                patchFailuresLeft--;
                throw new GcpReadException("instanceGroupManagers.patch", ErrorKind.CONFLICT, patchStatusToFail);
            }
            ObjectNode mig = gcp.mig();
            if (!mig.path("fingerprint").asText().equals(fingerprint)) {
                throw new GcpReadException("instanceGroupManagers.patch", ErrorKind.CONFLICT, 412);
            }
            if (body.has("versions")) {
                ArrayNode versions = JSON.createArrayNode();
                body.get("versions").forEach(v -> versions.add(v.deepCopy()));
                mig.set("versions", versions);
                mig.put("instanceTemplate", versions.path(0).path("instanceTemplate").asText());
            }
            if (body.has("updatePolicy")) {
                ObjectNode policy = mig.has("updatePolicy") ? (ObjectNode) mig.get("updatePolicy") : mig.putObject("updatePolicy");
                body.get("updatePolicy").fields().forEachRemaining(e -> policy.set(e.getKey(), e.getValue().deepCopy()));
            }
            if (body.has("targetSize")) {
                mig.put("targetSize", body.path("targetSize").asInt());
            }
            mig.put("fingerprint", "fp-" + (++seq));
            if (body.has("versions")) {
                ((ObjectNode) mig.path("status")).put("isStable", false);
                ((ObjectNode) mig.path("status").path("versionTarget")).put("isReached", false);
            }
            return JSON.createObjectNode().put("id", "op" + seq);
        }
    }

    final FakeGcp gcp = FakeGcp.healthy();
    final FakeMutating mutating = new FakeMutating(gcp);
    final TestClock clock = new TestClock();
    final MemoryRegistry registry = new MemoryRegistry();
    final MemorySnapshots snapshots = new MemorySnapshots();
    final OperationFacts facts = mock(OperationFacts.class);
    final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    final UUID opId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    final UUID groupId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    final UUID planId = UUID.randomUUID();
    final Map<UUID, String> progress = new HashMap<>();
    GcpStepServices services;
    GcpStepLibrary library;
    GcpProviderGate gate;
    GcpEligibilityEvaluator evaluator;
    GcpMachineShapes shapes;
    String key = KEY;
    boolean gateOpen = true;
    String lastProgress;

    GcpConversionFixture() {
        CloudAccount a = new CloudAccount();
        a.setId(3L);
        a.setProvider("GCP");
        a.setGcpProjectId(FakeGcp.PROJECT);
        when(accounts.findById(3L)).thenReturn(Optional.of(a));
        gcp.managedInstances.clear();
        resetInstances(true);
        wire();
    }

    void wire() {
        GcpViewSource views = acc -> gcp.view();
        shapes = new GcpMachineShapes();
        evaluator = new GcpEligibilityEvaluator(views, shapes, mock(EligibilityCache.class),
                new AutoSpottingMetrics(new SimpleMeterRegistry()), false, Clock.systemUTC());
        gate = new GcpProviderGate(new AutoSpottingSwitchProperties(true, true, true, true, false, gateOpen, false, false, false, false),
                new AutoSpottingFeatureFlags(false, gateOpen, false, false));
        when(facts.planConfirmAcks(any())).thenReturn(Optional.of("[\"FULL_SPOT_OPTIN\"]"));
        when(facts.operationProgress(any())).thenAnswer(i -> Optional.ofNullable(lastProgress));
        services = new GcpStepServices(accounts, views, acc -> mutating, gate, evaluator, new GcpMigStatusReader(), snapshots,
                registry, new GcpVersionTargetEvaluator(), facts, clock, Duration.ofDays(7));
        library = new GcpStepLibrary(services);
    }

    /** Six instances, all on the original template (a plain MIG), or empty. */
    void resetInstances(boolean all) {
        gcp.managedInstances.clear();
        if (all) {
            for (int i = 0; i < 6; i++) {
                gcp.managedInstances.add(instance(i, null, ORIGINAL, "NONE"));
            }
        }
    }

    static ObjectNode instance(int i, String version, String template, String action) {
        ObjectNode n = JSON.createObjectNode();
        n.put("instance", FakeGcp.P + "/zones/us-central1-" + "abc".charAt(i % 3) + "/instances/web-" + i);
        n.put("instanceStatus", "RUNNING");
        n.put("currentAction", action);
        ObjectNode v = n.putObject("version");
        if (version != null) {
            v.put("name", version);
        }
        v.put("instanceTemplate", template);
        return n;
    }

    /** The rollout completes: N on the original (xas-standard), the rest on the Spot copy (or all Spot when N = 0). */
    void completeRollout(int n, String spotLink) {
        gcp.managedInstances.clear();
        for (int i = 0; i < 6; i++) {
            boolean std = i < n;
            gcp.managedInstances.add(instance(i, std ? GcpMigFacts.STANDARD_VERSION : GcpMigFacts.SPOT_VERSION,
                    std ? ORIGINAL : spotLink, "NONE"));
        }
        ObjectNode status = (ObjectNode) gcp.mig().path("status");
        status.put("isStable", true);
        ((ObjectNode) status.path("versionTarget")).put("isReached", true);
    }

    /** The confirmed ENABLE target at floor {@code n}, as the plan wrote it. */
    ObjectNode target(int n) {
        ObjectNode t = JSON.createObjectNode();
        t.put("shape", "MIG");
        ArrayNode versions = t.putArray("versions");
        if (n > 0) {
            ObjectNode std = versions.addObject();
            std.put("name", GcpMigFacts.STANDARD_VERSION);
            std.put("instanceTemplate", ORIGINAL);
            std.putObject("targetSize").put("fixed", n);
        }
        versions.addObject().put("name", GcpMigFacts.SPOT_VERSION).put("instanceTemplate", "(XamOps Spot copy, created at enable)")
                .put("remainder", true);
        t.set("customerUpdatePolicy", gcp.mig().path("updatePolicy").deepCopy());
        return t;
    }

    GcpMigSpotProvider provider() {
        GcpViewSource views = acc -> gcp.view();
        return new GcpMigSpotProvider(accounts, views, evaluator, new GcpMigStatusReader(),
                new GcpEstimatePriceService(views, shapes, Clock.systemUTC()), gate, false, false, Clock.systemUTC(), library);
    }

    /** Swap the regional MIG for the zonal one (four instances, one zone). */
    void useZonalMig() {
        gcp.migs.clear();
        gcp.migs.add(FakeGcp.obj("mig-zonal-batch.json"));
        key = "xamops-test/us-central1-a/batch-mig";
        gcp.managedInstances.clear();
        for (int i = 0; i < 4; i++) {
            gcp.managedInstances.add(instance(i, null, ORIGINAL, "NONE"));
        }
    }

    SpotGroup group() {
        SpotGroup g = new SpotGroup();
        g.setId(groupId);
        g.setCloudAccountId(3L);
        g.setGroupKey(key);
        return g;
    }

    /** Records the plan's fingerprint against the live MIG, as confirm does. */
    void planAgainstLive() {
        String hash = GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, gcp.mig()));
        when(facts.planLiveFingerprint(planId)).thenReturn(Optional.of(hash));
    }

    OperationContext context(OperationKind kind, String targetJson, String compensationJson, OperationPhase phase) {
        return new OperationContext(opId, kind, group(), targetJson, compensationJson, phase, planId, null);
    }

    StepOutcome run(Step step, int seq) {
        return step.execute(new StepContext(opId, seq, mock(LeaseHandle.class), null, Duration.ofSeconds(30)));
    }

    /** Execute every step in order, stopping at the first non-Done outcome (a wait is returned to the caller). */
    List<StepOutcome> runUntilNotDone(StepProgram program) {
        List<StepOutcome> out = new ArrayList<>();
        for (int i = 0; i < program.size(); i++) {
            StepOutcome o = run(program.at(i), i);
            out.add(o);
            if (!(o instanceof StepOutcome.Done)) {
                break;
            }
        }
        return out;
    }

    /** Poll {@code step} until it is Done, advancing the clock; fails the test after 40 polls. */
    StepOutcome pollDone(Step step, int seq) {
        for (int i = 0; i < 40; i++) {
            StepOutcome o = run(step, seq);
            if (o instanceof StepOutcome.InProgress ip) {
                lastProgress = ip.progressJson();
                clock.advance(ip.pollAfter().plusSeconds(1));
                continue;
            }
            return o;
        }
        throw new AssertionError("step never finished: " + step.name());
    }

    // ---- in-memory stores ----

    static final class MemoryRegistry extends GcpTemplateRegistry {
        final List<Row> rows = new ArrayList<>();
        final Map<UUID, String> status = new HashMap<>();
        final Map<UUID, String> gcpIds = new HashMap<>();
        final Map<UUID, String> insertOps = new HashMap<>();
        final Map<UUID, Instant> deleteAfter = new HashMap<>();

        MemoryRegistry() {
            super(null);
        }

        private Row current(Row r) {
            return new Row(r.id(), r.groupId(), r.generation(), r.role(), r.name(), r.scope(), r.selfLink(),
                    gcpIds.getOrDefault(r.id(), r.gcpId()), r.sourceSelfLink(), r.bodySha256(), r.createdByOp(),
                    status.getOrDefault(r.id(), r.status()), deleteAfter.get(r.id()), insertOps.get(r.id()));
        }

        @Override
        public Row registerCreating(UUID groupId, int generation, String role, String name, String scope, String selfLink,
                                    String sourceSelfLink, String sourceGcpId, String bodySha256, UUID opId, Instant now) {
            Optional<Row> existing = findLive(scope, name);
            if (existing.isPresent()) {
                return existing.get();
            }
            Row r = new Row(UUID.randomUUID(), groupId, generation, role, name, scope, selfLink, null, sourceSelfLink,
                    bodySha256, opId, "CREATING", null, null);
            rows.add(r);
            return current(r);
        }

        @Override
        public Optional<Row> findLive(String scope, String name) {
            return rows.stream().map(this::current).filter(r -> r.scope().equals(scope) && r.name().equals(name)
                    && List.of("CREATING", "IN_USE", "RETIRED", "DELETING").contains(r.status())).findFirst();
        }

        @Override
        public Optional<Row> findByOp(UUID opId) {
            return rows.stream().map(this::current).filter(r -> r.createdByOp().equals(opId)).findFirst();
        }

        @Override
        public int maxGeneration(UUID groupId) {
            return rows.stream().filter(r -> r.groupId().equals(groupId)).mapToInt(Row::generation).max().orElse(0);
        }

        @Override
        public void markInUse(UUID id, String gcpId, Instant now) {
            gcpIds.putIfAbsent(id, gcpId);
            status.put(id, "IN_USE");
        }

        @Override
        public void setInsertOperation(UUID id, String op, Instant now) {
            insertOps.put(id, op);
        }

        final List<UUID> deleting = new ArrayList<>();

        @Override
        public List<Row> deletionCandidates(Instant now) {
            return rows.stream().map(this::current).filter(r -> "RETIRED".equals(r.status()) && r.gcpId() != null
                    && r.deleteAfter() != null && r.deleteAfter().isBefore(now)).toList();
        }

        @Override
        public boolean markDeleting(UUID id, Instant now) {
            status.put(id, "DELETING");
            deleting.add(id);
            return true;
        }

        @Override
        public void markDeleted(UUID id, Instant now) {
            status.put(id, "DELETED");
        }

        @Override
        public void backToRetired(UUID id, Duration retention, Instant now) {
            status.put(id, "RETIRED");
            deleteAfter.put(id, now.plus(retention));
        }

        @Override
        public void markGone(UUID id, Instant now) {
            status.put(id, "GONE");
        }

        @Override
        public int retireUnreferenced(UUID groupId, java.util.Collection<String> links, Duration retention, Instant now) {
            int n = 0;
            for (Row r : rows.stream().map(this::current).filter(x -> x.groupId().equals(groupId)).toList()) {
                boolean referenced = links.stream().anyMatch(l -> sameTemplate(l, r.selfLink()));
                if (!referenced && "IN_USE".equals(r.status())) {
                    status.put(r.id(), "RETIRED");
                    deleteAfter.put(r.id(), now.plus(retention));
                    n++;
                }
            }
            return n;
        }
    }

    static final class MemorySnapshots extends GcpSnapshotStore {
        final Map<UUID, Stored> byOp = new HashMap<>();
        final List<String> order = new ArrayList<>();

        MemorySnapshots() {
            super(null, null);
        }

        @Override
        public Optional<Stored> findByOp(UUID operationId) {
            return Optional.ofNullable(byOp.get(operationId));
        }

        @Override
        public Optional<Stored> findLinked(UUID groupId) {
            return byOp.values().stream().filter(s -> s.groupId().equals(groupId)).findFirst();
        }

        @Override
        public Stored persist(LeaseHandle handle, UUID operationId, UUID groupId, SnapshotPayload payload, Instant now) {
            Stored s = new Stored(UUID.randomUUID(), groupId, payload.exact(), payload.payloadJson(), payload.payloadSha256());
            byOp.put(operationId, s);
            order.add("snapshot");
            return s;
        }
    }
}
