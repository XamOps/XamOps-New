package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** One fixture per eligibility code (design-gcp.md §6.1), and a fault injected into each read. */
class GcpEligibilityEvaluatorTest {
    private static final String KEY = "xamops-test/us-central1/web-mig";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Profile conservative = new Profile("conservative", null);

    private CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(5L);
        a.setProvider("GCP");
        a.setGcpProjectId(FakeGcp.PROJECT);
        return a;
    }

    private GcpEligibilityEvaluator evaluator(FakeGcp gcp, boolean autoscaledFloorAllowed) {
        GcpViewSource views = acct -> gcp.view();
        return new GcpEligibilityEvaluator(views, new GcpMachineShapes(), mock(EligibilityCache.class),
                new AutoSpottingMetrics(registry), autoscaledFloorAllowed, Clock.systemUTC());
    }

    private EligibilityResult plan(FakeGcp gcp) {
        return evaluator(gcp, false).evaluate(account(), gcp.view(), KEY, null, null, conservative, EvalMode.PLAN,
                new HashMap<>()).result();
    }

    private static List<String> codes(List<Check> checks) {
        return checks.stream().map(Check::code).toList();
    }

    private EligibilityResult with(Consumer<FakeGcp> mutate) {
        FakeGcp gcp = FakeGcp.healthy();
        mutate.accept(gcp);
        return plan(gcp);
    }

    @Test
    void aHealthyMigIsEligible() {
        EligibilityResult r = plan(FakeGcp.healthy());
        assertThat(r.blockers()).isEmpty();
        assertThat(r.eligible()).isTrue();
    }

    @Test
    void migNotFoundOnlyOnAConfirmed404() {
        assertThat(codes(with(g -> g.migs.clear()).blockers())).containsExactly("GCP_MIG_NOT_FOUND");
        EligibilityResult failed = with(g -> g.fail("instanceGroupManagers.get", ErrorKind.PERMISSION));
        assertThat(codes(failed.blockers())).containsExactly("CHECK_FAILED");
        assertThat(failed.blockers().get(0).errorKind()).isEqualTo(ErrorKind.PERMISSION);
    }

    @Test
    void aReplacedMigIdIsNotFound() {
        FakeGcp gcp = FakeGcp.healthy();
        EligibilityResult r = evaluator(gcp, false).evaluate(account(), gcp.view(), KEY, "999", null, conservative,
                EvalMode.PLAN, new HashMap<>()).result();
        assertThat(codes(r.blockers())).containsExactly("GCP_MIG_NOT_FOUND");
    }

    @Test
    void locationUnknownWhenTheResourceNamesNoScopeOrNoZones() {
        assertThat(codes(with(g -> g.mig().remove("region")).blockers())).containsExactly("GCP_LOCATION_UNKNOWN");
        assertThat(codes(with(g -> g.mig().putObject("distributionPolicy").putArray("zones")).blockers()))
                .containsExactly("GCP_LOCATION_UNKNOWN");
    }

    @Test
    void gkeNodePoolBlockedByLabel() {
        EligibilityResult r = with(g -> ((ObjectNode) g.template().path("properties").path("labels")).put("goog-gke-node", ""));
        assertThat(codes(r.blockers())).contains("GCP_GKE_NODE_POOL");
        assertThat(r.blockers().get(0).code()).isEqualTo("GCP_GKE_NODE_POOL");
    }

    @Test
    void gkeNodePoolBlockedByNamePrefix() {
        FakeGcp renamed = FakeGcp.healthy();
        renamed.mig().put("name", "gke-prod-pool-1-abc123-grp");
        EligibilityResult byMig = evaluator(renamed, false).evaluate(account(), renamed.view(),
                "xamops-test/us-central1/gke-prod-pool-1-abc123-grp", null, null, conservative, EvalMode.PLAN,
                new HashMap<>()).result();
        assertThat(codes(byMig.blockers())).contains("GCP_GKE_NODE_POOL");
        EligibilityResult byTemplate = with(g -> g.template().put("name", "gke-prod-pool-1-abc123"));
        assertThat(codes(byTemplate.blockers())).contains("GCP_GKE_NODE_POOL");
    }

    @Test
    void managedServiceLabels() {
        for (String label : List.of("goog-dataproc-cluster-name", "goog-batch-job-uid")) {
            EligibilityResult r = with(g -> ((ObjectNode) g.template().path("properties").path("labels")).put(label, "x"));
            assertThat(codes(r.blockers())).as(label).contains("GCP_MANAGED_SERVICE");
        }
    }

    @Test
    void statefulFromPolicyStatusOrPerInstanceConfigs() {
        assertThat(codes(with(g -> g.mig().putObject("statefulPolicy").putObject("preservedState").putObject("disks")
                .putObject("data").put("autoDelete", "NEVER")).blockers())).contains("GCP_STATEFUL");
        assertThat(codes(with(g -> ((ObjectNode) g.mig().path("status")).putObject("stateful").put("hasStatefulConfig", true))
                .blockers())).contains("GCP_STATEFUL");
        assertThat(codes(with(g -> g.perInstanceConfigs.add(FakeGcp.JSON.createObjectNode().put("name", "web-1")))
                .blockers())).contains("GCP_STATEFUL");
    }

    @Test
    void sizeTooSmallFromTargetSizeOrAutoscalerMinimum() {
        assertThat(codes(with(g -> g.mig().put("targetSize", 1)).blockers())).contains("GCP_SIZE_TOO_SMALL");
        EligibilityResult r = with(g -> {
            ((ObjectNode) g.mig().path("status")).put("autoscaler", FakeGcp.P + "/regions/us-central1/autoscalers/web-as");
            g.autoscalers.add(FakeGcp.JSON.createObjectNode().put("selfLink", FakeGcp.P + "/regions/us-central1/autoscalers/web-as")
                    .set("autoscalingPolicy", FakeGcp.JSON.createObjectNode().put("minNumReplicas", 1).put("maxNumReplicas", 9)));
        });
        assertThat(codes(r.blockers())).contains("GCP_SIZE_TOO_SMALL");
    }

    @Test
    void sharedCoreTypeMissingFromTheTableIsUnsupported() {
        EligibilityResult r = with(g -> {
            g.template().with("properties").put("machineType", "e2-future-shared");
            g.machineTypes.put("e2-future-shared", FakeGcp.fixture("machinetype-e2-future-shared.json"));
        });
        assertThat(codes(r.blockers())).contains("GCP_SHARED_CORE_UNSUPPORTED");
        // A type in the table is fine.
        EligibilityResult ok = with(g -> {
            g.template().with("properties").put("machineType", "e2-small");
            g.machineTypes.put("e2-small", FakeGcp.fixture("machinetype-e2-small.json"));
        });
        assertThat(codes(ok.blockers())).doesNotContain("GCP_SHARED_CORE_UNSUPPORTED");
    }

    @Test
    void multiVersionForeignUnlessTheXamopsPair() {
        EligibilityResult foreign = with(g -> {
            g.mig().putArray("versions").addObject().put("name", "canary").put("instanceTemplate", FakeGcp.TEMPLATE);
            g.mig().withArray("versions").addObject().put("name", "stable").put("instanceTemplate", FakeGcp.TEMPLATE);
        });
        assertThat(codes(foreign.blockers())).contains("GCP_MULTI_VERSION_FOREIGN");
        EligibilityResult pair = with(g -> {
            g.mig().putArray("versions").addObject().put("name", "xas-standard").put("instanceTemplate", FakeGcp.TEMPLATE);
            g.mig().withArray("versions").addObject().put("name", "xas-spot").put("instanceTemplate", FakeGcp.TEMPLATE);
        });
        assertThat(codes(pair.blockers())).doesNotContain("GCP_MULTI_VERSION_FOREIGN");
    }

    @Test
    void updateInProgress() {
        assertThat(codes(with(g -> ((ObjectNode) g.mig().path("status")).put("isStable", false)).blockers()))
                .contains("GCP_UPDATE_IN_PROGRESS");
        assertThat(codes(with(g -> ((ObjectNode) g.mig().path("status")).putObject("versionTarget").put("isReached", false))
                .blockers())).contains("GCP_UPDATE_IN_PROGRESS");
    }

    @Test
    void standbyPool() {
        assertThat(codes(with(g -> g.mig().put("targetStoppedSize", 2)).blockers())).contains("GCP_STANDBY_POOL");
        assertThat(codes(with(g -> g.mig().put("targetSuspendedSize", 1)).blockers())).contains("GCP_STANDBY_POOL");
    }

    @Test
    void machineUnsupportedForA4xAndBareMetal() {
        for (String type : List.of("a4x-highgpu-4g", "c3-standard-192-metal")) {
            EligibilityResult r = with(g -> {
                g.template().with("properties").put("machineType", type);
                g.machineTypes.put(type, FakeGcp.JSON.createObjectNode().put("guestCpus", 4).put("memoryMb", 16384)
                        .put("isSharedCpu", false));
            });
            assertThat(codes(r.blockers())).as(type).contains("GCP_MACHINE_UNSUPPORTED");
        }
    }

    @Test
    void disruptionPolicyBelowReplace() {
        for (String action : List.of("NONE", "REFRESH", "RESTART")) {
            EligibilityResult r = with(g -> ((ObjectNode) g.mig().path("updatePolicy")).put("mostDisruptiveAllowedAction", action));
            assertThat(codes(r.blockers())).as(action).contains("GCP_DISRUPTION_POLICY");
        }
    }

    @Test
    void templateUnpreservableForSpecificReservationOrSoleTenant() {
        assertThat(codes(with(g -> g.template().with("properties").with("reservationAffinity")
                .put("consumeReservationType", "SPECIFIC_RESERVATION")).blockers())).contains("GCP_TEMPLATE_UNPRESERVABLE");
        assertThat(codes(with(g -> g.template().with("properties").with("scheduling").putArray("nodeAffinities")
                .addObject().put("key", "node-group")).blockers())).contains("GCP_TEMPLATE_UNPRESERVABLE");
    }

    @Test
    void acceleratorsAndLocalSsdAreHardBlockers() {
        assertThat(codes(with(g -> g.template().with("properties").putArray("guestAccelerators").addObject()
                .put("acceleratorType", "nvidia-tesla-t4").put("acceleratorCount", 1)).blockers())).contains("GCP_ACCELERATORS");
        assertThat(codes(with(g -> g.template().with("properties").withArray("disks").addObject()
                .put("type", "SCRATCH").put("interface", "NVME")).blockers())).contains("GCP_LOCAL_SSD");
    }

    @Test
    void quotaSurgeWhenUsagePlusSurgeExceedsTheLimit() {
        EligibilityResult r = with(g -> g.regions.put("us-central1", FakeGcp.JSON.createObjectNode().set("quotas",
                FakeGcp.array().add(FakeGcp.JSON.createObjectNode().put("metric", "CPUS").put("limit", 100.0).put("usage", 95.0)))));
        assertThat(codes(r.blockers())).contains("GCP_QUOTA_SURGE");
    }

    @Test
    void permissionMissingOnTheProjectOrActAs() {
        EligibilityResult noCreate = with(g -> g.grantedProject.remove("compute.instanceTemplates.create"));
        assertThat(codes(noCreate.blockers())).contains("GCP_PERMISSION");
        assertThat(noCreate.blockers().stream().filter(b -> "GCP_PERMISSION".equals(b.code())).findFirst().orElseThrow().text())
                .contains("compute.instanceTemplates.create");
        EligibilityResult noActAs = with(g -> g.actAsDenied.add("web-sa@xamops-test.iam.gserviceaccount.com"));
        assertThat(codes(noActAs.blockers())).contains("GCP_PERMISSION");
        assertThat(noActAs.blockers().get(0).text()).contains("actAs");
    }

    @Test
    void accountReadOnlyBlocksButANullAccessTypeNeverDoes() {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount ro = account();
        ro.setAccessType("read-only");
        EligibilityResult blocked = evaluator(gcp, false).evaluate(ro, gcp.view(), KEY, null, null, conservative,
                EvalMode.PLAN, new HashMap<>()).result();
        assertThat(codes(blocked.blockers())).contains("GCP_ACCOUNT_READ_ONLY");
        assertThat(account().getAccessType()).isNull();
        assertThat(codes(plan(FakeGcp.healthy()).blockers())).doesNotContain("GCP_ACCOUNT_READ_ONLY");
    }

    @Test
    void storedReadOnlyTypeBlocksAndAnOutdatedRoleIsReportedOnlyForCheckedProjects() {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount ro = account();
        ro.setGcpAccessType("READ_ONLY");
        assertThat(codes(evaluator(gcp, false).evaluate(ro, gcp.view(), KEY, null, null, conservative, EvalMode.PLAN,
                new HashMap<>()).result().blockers())).contains("GCP_ACCOUNT_READ_ONLY").doesNotContain("GCP_ROLE_OUTDATED");
        // The fake holds the 7 permissions of the plan check, not the 14 of role v1: outdated for a checked project.
        CloudAccount as = account();
        as.setGcpAccessType("AUTOSPOTTING");
        assertThat(codes(evaluator(gcp, false).evaluate(as, gcp.view(), KEY, null, null, conservative, EvalMode.PLAN,
                new HashMap<>()).result().blockers())).contains("GCP_ROLE_OUTDATED");
        FakeGcp full = FakeGcp.healthy();
        full.grantedProject.addAll(GcpAccessValidator.currentRolePermissions());
        assertThat(codes(evaluator(full, false).evaluate(as, full.view(), KEY, null, null, conservative, EvalMode.PLAN,
                new HashMap<>()).result().blockers())).doesNotContain("GCP_ROLE_OUTDATED");
        // Null type (not checked yet): never runs the check.
        assertThat(codes(plan(FakeGcp.healthy()).blockers())).doesNotContain("GCP_ROLE_OUTDATED");
    }
    @Test
    void noReadinessGateForALoadBalancedMigWithoutAutohealing() {
        EligibilityResult blocked = with(g -> g.backendServices.add(backend(g)));
        assertThat(codes(blocked.blockers())).contains("GCP_NO_READINESS_GATE");
        // With an autohealing health check the gate exists: not blocked, and the preemption-notice warning shows.
        EligibilityResult gated = with(g -> {
            g.backendServices.add(backend(g));
            g.mig().putArray("autoHealingPolicies").addObject().put("healthCheck", FakeGcp.P + "/global/healthChecks/hc");
        });
        assertThat(codes(gated.blockers())).doesNotContain("GCP_NO_READINESS_GATE");
        assertThat(codes(gated.warnings())).contains("GCP_LB_OR_AUTOHEAL");
    }

    private static ObjectNode backend(FakeGcp g) {
        ObjectNode bs = FakeGcp.JSON.createObjectNode().put("name", "web-bs");
        bs.putArray("backends").addObject().put("group", g.mig().path("instanceGroup").asText());
        return bs;
    }

    @Test
    void floorShapeUnverifiedForAnAutoscaledMigUntilTheFlagIsOn() {
        FakeGcp gcp = FakeGcp.healthy();
        String link = FakeGcp.P + "/regions/us-central1/autoscalers/web-as";
        ((ObjectNode) gcp.mig().path("status")).put("autoscaler", link);
        gcp.autoscalers.add(FakeGcp.JSON.createObjectNode().put("selfLink", link)
                .set("autoscalingPolicy", FakeGcp.JSON.createObjectNode().put("minNumReplicas", 3).put("maxNumReplicas", 9)));
        EligibilityResult off = evaluator(gcp, false).evaluate(account(), gcp.view(), KEY, null, null, conservative,
                EvalMode.PLAN, new HashMap<>()).result();
        assertThat(codes(off.blockers())).containsExactly("GCP_FLOOR_SHAPE_UNVERIFIED");
        EligibilityResult on = evaluator(gcp, true).evaluate(account(), gcp.view(), KEY, null, null, conservative,
                EvalMode.PLAN, new HashMap<>()).result();
        assertThat(on.blockers()).isEmpty();
        // Full Spot has no floor, so the unverified-shape blocker does not apply.
        EligibilityResult fullSpot = evaluator(gcp, false).evaluate(account(), gcp.view(), KEY, null, null,
                new Profile("full_spot", null), EvalMode.PLAN, new HashMap<>()).result();
        assertThat(codes(fullSpot.blockers())).doesNotContain("GCP_FLOOR_SHAPE_UNVERIFIED");
    }

    @Test
    void iacMarkersAreAnAcknowledgedWarningNotABlocker() {
        EligibilityResult r = with(g -> ((ObjectNode) g.template().path("properties").path("labels"))
                .put("goog-terraform-provisioned", "true"));
        assertThat(r.blockers()).isEmpty();
        assertThat(codes(r.warnings())).contains("GCP_IAC_MARKERS");
    }

    @Test
    void otherWarnings() {
        FakeGcp gcp = FakeGcp.healthy();
        gcp.mig().putObject("allInstancesConfig").putObject("properties");
        gcp.mig().with("allInstancesConfig").with("properties").with("labels").put("a", "b");
        gcp.template().with("properties").putObject("resourceManagerTags").put("tagKeys/1", "tagValues/2");
        EligibilityResult r = plan(gcp);
        assertThat(codes(r.warnings())).contains("GCP_ALL_INSTANCES_CONFIG", "GCP_SECURE_TAGS");
        assertThat(codes(plan(FakeGcp.healthy()).warnings())).doesNotContain("GCP_SINGLE_ZONE");
    }

    @Test
    void aFaultInjectedIntoEachReadGivesCheckFailedNeverAPass() {
        Map<String, String> faults = Map.of(
                "instanceTemplates.get", "b-CHECK_FAILED-TEMPLATE_READ",
                "instanceGroupManagers.listPerInstanceConfigs", "b-CHECK_FAILED-GCP_STATEFUL",
                "machineTypes.get", "b-CHECK_FAILED-GCP_SHARED_CORE_UNSUPPORTED",
                "projects.testIamPermissions", "b-CHECK_FAILED-GCP_PERMISSION",
                "serviceAccounts.testIamPermissions", "b-CHECK_FAILED-GCP_PERMISSION",
                "backendServices.aggregatedList", "b-CHECK_FAILED-GCP_NO_READINESS_GATE");
        for (Map.Entry<String, String> f : faults.entrySet()) {
            EligibilityResult r = with(g -> g.fail(f.getKey(), ErrorKind.TRANSIENT));
            assertThat(r.blockers()).as(f.getKey()).anyMatch(b -> b.id().equals(f.getValue())
                    && "CHECK_FAILED".equals(b.code()) && b.errorKind() == ErrorKind.TRANSIENT);
            assertThat(r.eligible()).as(f.getKey()).isFalse();
        }
        // regions.get is read only once the shape is known; autoscalers.get only for an autoscaled MIG.
        EligibilityResult region = with(g -> g.fail("regions.get", ErrorKind.THROTTLED));
        assertThat(region.blockers()).anyMatch(b -> b.id().equals("b-CHECK_FAILED-GCP_QUOTA_SURGE")
                && b.errorKind() == ErrorKind.THROTTLED);
        EligibilityResult autoscaler = with(g -> {
            ((ObjectNode) g.mig().path("status")).put("autoscaler", FakeGcp.P + "/regions/us-central1/autoscalers/web-as");
            g.fail("autoscalers.get", ErrorKind.PERMISSION);
        });
        assertThat(autoscaler.blockers()).anyMatch(b -> b.id().equals("b-CHECK_FAILED-AUTOSCALER"));
    }

    @Test
    void listModeRunsOnlyWhatTheListAnswersAndDefersTheRest() {
        FakeGcp gcp = FakeGcp.healthy();
        EligibilityResult r = evaluator(gcp, false).evaluate(account(), gcp.view(), KEY, null, gcp.mig(), null,
                EvalMode.LIST, new HashMap<>()).result();
        assertThat(r.deferred()).extracting(Check::code).contains("GCP_PERMISSION", "GCP_QUOTA_SURGE", "GCP_STATEFUL");
        assertThat(gcp.count("projects.testIamPermissions")).isZero();
        assertThat(gcp.count("regions.get")).isZero();
        assertThat(gcp.count("machineTypes.get")).isZero();
        assertThat(gcp.count("instanceGroupManagers.get")).as("the MIG came from the list page").isZero();
    }

    @Test
    void listModeReadsEachDistinctTemplateOncePerRequestAndExcludesGkePools() {
        FakeGcp gcp = FakeGcp.healthy();
        ObjectNode second = gcp.mig().deepCopy();
        second.put("name", "web-mig-2").put("id", "2");
        gcp.migs.add(second);
        GcpEligibilityEvaluator ev = evaluator(gcp, false);
        Map<String, com.xammer.cloud.service.autospotting.aws.Sig<com.fasterxml.jackson.databind.JsonNode>> memo = new HashMap<>();
        ev.evaluate(account(), gcp.view(), KEY, null, gcp.migs.get(0), null, EvalMode.LIST, memo);
        ev.evaluate(account(), gcp.view(), "xamops-test/us-central1/web-mig-2", null, gcp.migs.get(1), null, EvalMode.LIST, memo);
        assertThat(gcp.count("instanceTemplates.get")).isEqualTo(1);
        ((ObjectNode) gcp.template().path("properties").path("labels")).put("goog-gke-node", "");
        EligibilityResult gke = ev.evaluate(account(), gcp.view(), KEY, null, gcp.migs.get(0), null, EvalMode.LIST,
                new HashMap<>()).result();
        assertThat(codes(gke.blockers())).contains("GCP_GKE_NODE_POOL");
        assertThat(registry.find("autospotting.eligibility.blocked").counters()).isNotEmpty();
    }

    @Test
    void readsNeverMutate() {
        FakeGcp gcp = FakeGcp.healthy();
        plan(gcp);
        assertThat(gcp.calls).allMatch(c -> c.contains("get") || c.contains("list") || c.contains("List")
                || c.contains("aggregatedList") || c.contains("testIamPermissions") || c.contains("Get"));
    }
}
