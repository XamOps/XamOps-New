package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.ops.OperationFacts;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsResponse;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsResponse;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stubbed AWS for the conversion programs (no real call, ever): the read fakes of {@link FakeAws},
 * a separate mock {@code AutoScalingClient} behind {@link AwsMutatingClient} that records every write
 * and applies it to the fake group, an in-memory snapshot store, and the real step services over them.
 * {@link #log} records the order of the interesting events so a test can assert "snapshot before the
 * first update, marker last".
 */
final class AwsConversionFixture {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRODUCT = "{\"terms\":{\"OnDemand\":{\"S.J\":{\"priceDimensions\":{\"S.J.6\":"
            + "{\"pricePerUnit\":{\"USD\":\"0.0960000000\"}}}}}},\"version\":\"20261101\","
            + "\"publicationDate\":\"2026-11-01T00:00:00Z\"}";

    /** A clock a test can move. */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An in-memory snapshot store that records when it was written. */
    final class MemorySnapshots extends AwsSnapshotStore {
        Stored stored;
        Stored linked;

        MemorySnapshots() {
            super(null, null);
        }

        @Override
        public Optional<Stored> findByOp(UUID operationId) {
            return Optional.ofNullable(stored);
        }

        @Override
        public Optional<Stored> findLinked(UUID groupId) {
            return Optional.ofNullable(linked);
        }

        @Override
        public Stored persist(LeaseHandle handle, UUID operationId, UUID groupId, SnapshotPayload payload,
                              Instant now) {
            log.add("snapshot");
            stored = new Stored(UUID.randomUUID(), groupId, payload.exact(), payload.payloadJson(),
                    payload.payloadSha256());
            return stored;
        }
    }

    final CloudAccount account = FakeAws.account();
    final FakeAws aws = new FakeAws();
    final AutoScalingClient write = FakeAws.sdkMock(AutoScalingClient.class);
    final AwsMutatingClient client = new AwsMutatingClient(FakeAws.REGION, write);
    final List<String> log = new ArrayList<>();
    final List<UpdateAutoScalingGroupRequest> updates = new ArrayList<>();
    final Set<String> spotInstances = new HashSet<>();
    /**
     * Whether an update changed the group's purchase mix or shape, which is what makes Auto Scaling replace instances.
     * An update that leaves the distribution as it was starts no activity, so Spot stays (review High #2): tests that
     * model the replacement clear {@link #spotInstances} only when this is true.
     */
    boolean replacementStarted;
    final MovableClock clock = new MovableClock(Instant.parse("2026-11-17T10:00:00Z"));
    final MemorySnapshots snapshots = new MemorySnapshots();
    final AutoSpottingSwitchProperties switches = mock(AutoSpottingSwitchProperties.class);
    final OperationFacts facts = mock(OperationFacts.class);
    final AuditRedactor redactor = new AuditRedactor(JSON);
    final AwsLaunchEventRecorder events = mock(AwsLaunchEventRecorder.class);
    final GroupLookup groupLookup = mock(GroupLookup.class);
    final UUID groupId = UUID.randomUUID();
    final UUID opId = UUID.randomUUID();
    final UUID planId = UUID.randomUUID();
    AwsViewSource views;
    CloudAccountRepository accounts;
    AwsEligibilityEvaluator evaluator;
    AwsEstimatePriceService prices;
    AwsPlanReader planReader;
    AwsStepServices services;
    AwsSpotProvider provider;
    AwsStepLibrary library;
    AwsProviderGate gate;
    SpotOperation op = new SpotOperation();

    AwsConversionFixture(AutoScalingGroup group, boolean mutationsOpen) {
        aws.happyPath(group);
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(PRODUCT).build());
        views = (a, r) -> aws.view();
        prices = new AwsEstimatePriceService(pricing, views);
        AwsCommitmentUtilization commitments = mock(AwsCommitmentUtilization.class);
        when(commitments.thresholdPercent()).thenReturn(80.0);
        when(commitments.utilization(any())).thenReturn(Optional.empty());
        planReader = new AwsPlanReader(prices);
        evaluator = new AwsEligibilityEvaluator(views, new AwsGroupStatusReader(),
                planReader, mock(EligibilityCache.class), commitments, new AwsRegionCoverage() {
            @Override
            public boolean covered(CloudAccount a, String region) {
                return true;
            }
        }, mock(GroupLookup.class), new AutoSpottingMetrics(new SimpleMeterRegistry()), 12);
        accounts = mock(CloudAccountRepository.class);
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(switches.v2MutationsEnabled(true)).thenReturn(mutationsOpen);
        gate = new AwsProviderGate(switches, new AutoSpottingFeatureFlags(mutationsOpen, false, false, false));
        when(facts.operationProgress(any())).thenReturn(Optional.empty());
        when(facts.stepAttempts(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        AwsStatusAndRollout rolloutParts = new AwsStatusAndRollout();
        services = new AwsStepServices(accounts, views, (a, r) -> client, gate, evaluator, planReader,
                new AwsGroupStatusReader(), snapshots, facts, redactor, rolloutParts.observer,
                rolloutParts.evaluator, events, clock);
        library = new AwsStepLibrary(services);
        provider = new AwsSpotProvider(accounts, views, evaluator, new AwsGroupStatusReader(), prices, switches, gate,
                library, new AwsRestoreProgram(services, groupLookup), rolloutParts.evaluator, fallbackRules(),
                new AwsFallbackPrograms(services, fallbackRules(),
                        com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults()), events, clock);
        wireWrites();
    }

    /**
     * A provider over real repositories and a real snapshot store and event recorder (the PG ITs), with the
     * same stubbed AWS reads and writes.
     */
    AwsSpotProvider providerWith(OperationFacts realFacts, AwsSnapshotStore realSnapshots,
                                 AwsLaunchEventRecorder realEvents,
                                 java.util.function.Function<AwsStepServices, AwsRevertProgramFactory> revert) {
        AwsStatusAndRollout parts = new AwsStatusAndRollout();
        AwsStepServices real = new AwsStepServices(accounts, views, (a, r) -> client, gate, evaluator, planReader,
                new AwsGroupStatusReader(), realSnapshots, realFacts, redactor, parts.observer,
                parts.evaluator, realEvents, clock);
        return new AwsSpotProvider(accounts, views, evaluator, new AwsGroupStatusReader(), prices, switches, gate,
                new AwsStepLibrary(real), revert.apply(real), parts.evaluator, fallbackRules(),
                new AwsFallbackPrograms(real, fallbackRules(),
                        com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults()), realEvents, clock);
    }

    static AwsFallbackRules fallbackRules() {
        return new AwsFallbackRules(com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults());
    }
    /** The observer and evaluator the services and the provider share. */
    private static final class AwsStatusAndRollout {
        final AwsRolloutObserver observer = new AwsRolloutObserver();
        final AwsRolloutEvaluator evaluator = new AwsRolloutEvaluator(AwsRolloutProperties.defaults());
    }

    private void wireWrites() {
        doAnswer(inv -> {
            UpdateAutoScalingGroupRequest r = inv.getArgument(0);
            log.add("update");
            updates.add(r);
            var before = group();
            applyUpdate(r);
            var after = group();
            if (!java.util.Objects.equals(distribution(before), distribution(after))
                    || !java.util.Objects.equals(before.launchTemplate(), after.launchTemplate())) {
                replacementStarted = true;
            }
            return UpdateAutoScalingGroupResponse.builder().build();
        }).when(write).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
        doAnswer(inv -> {
            CreateOrUpdateTagsRequest r = inv.getArgument(0);
            log.add("tags");
            r.tags().forEach(t -> addTag(t.resourceId(), t.key(), t.value()));
            return CreateOrUpdateTagsResponse.builder().build();
        }).when(write).createOrUpdateTags(any(CreateOrUpdateTagsRequest.class));
        doAnswer(inv -> {
            DeleteTagsRequest r = inv.getArgument(0);
            log.add("deleteTags");
            r.tags().forEach(t -> removeTag(t.key()));
            return DeleteTagsResponse.builder().build();
        }).when(write).deleteTags(any(DeleteTagsRequest.class));
        doAnswer(inv -> {
            DescribeInstancesRequest r = inv.getArgument(0);
            List<String> ids = r.filters().isEmpty() ? List.of() : r.filters().get(0).values();
            List<software.amazon.awssdk.services.ec2.model.Instance> out = new ArrayList<>();
            for (String id : ids) {
                out.add(software.amazon.awssdk.services.ec2.model.Instance.builder().instanceId(id)
                        .instanceLifecycle(spotInstances.contains(id) ? "spot" : null).build());
            }
            return DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(out).build())
                    .build();
        }).when(aws.ec2).describeInstances(any(DescribeInstancesRequest.class));
    }

    AutoScalingGroup group() {
        return aws.groups.get(0);
    }

    void replaceGroup(AutoScalingGroup g) {
        aws.groups(g);
    }

    private static Object distribution(AutoScalingGroup g) {
        return g.mixedInstancesPolicy() == null ? null : java.util.List.of(
                String.valueOf(g.mixedInstancesPolicy().instancesDistribution()),
                String.valueOf(g.mixedInstancesPolicy().launchTemplate()));
    }
    void applyUpdate(UpdateAutoScalingGroupRequest r) {
        AutoScalingGroup.Builder b = group().toBuilder();
        if (r.capacityRebalance() != null) {
            b.capacityRebalance(r.capacityRebalance());
        }
        if (r.instanceMaintenancePolicy() != null) {
            var imp = r.instanceMaintenancePolicy();
            boolean clear = Integer.valueOf(-1).equals(imp.minHealthyPercentage())
                    && Integer.valueOf(-1).equals(imp.maxHealthyPercentage());
            b.instanceMaintenancePolicy(clear ? null : imp);
        }
        if (r.mixedInstancesPolicy() != null) {
            MixedInstancesPolicy want = r.mixedInstancesPolicy();
            MixedInstancesPolicy old = group().mixedInstancesPolicy();
            LaunchTemplate lt = want.launchTemplate() != null ? want.launchTemplate()
                    : old != null ? old.launchTemplate() : null;
            b.mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(lt)
                    .instancesDistribution(want.instancesDistribution()).build());
            b.launchTemplate((software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification) null);
        }
        if (r.launchTemplate() != null) {
            b.launchTemplate(r.launchTemplate()).mixedInstancesPolicy((MixedInstancesPolicy) null);
        }
        aws.groups(b.build());
    }

    private void addTag(String resource, String key, String value) {
        List<TagDescription> tags = new ArrayList<>(group().tags());
        tags.removeIf(t -> t.key().equals(key));
        tags.add(TagDescription.builder().key(key).value(value).propagateAtLaunch(false)
                .resourceId(resource).resourceType("auto-scaling-group").build());
        aws.groups(group().toBuilder().tags(tags).build());
    }

    private void removeTag(String key) {
        List<TagDescription> tags = new ArrayList<>(group().tags());
        tags.removeIf(t -> t.key().equals(key));
        aws.groups(group().toBuilder().tags(tags).build());
    }

    // ---- plan, op and program ----

    SpotGroup spotGroup() {
        SpotGroup g = new SpotGroup();
        g.setId(groupId);
        g.setCloudAccountId(7L);
        g.setGroupKey(FakeAws.REGION + "/web-asg");
        g.setState(GroupState.APPLYING);
        g.setProvider(com.xammer.cloud.domain.autospotting.Provider.AWS);
        return g;
    }

    /** Plan the group with {@code profile}; also stores a plan row whose fingerprint is the live one. */
    PlanBody plan(String profile) {
        return plan(profile, OperationKind.ENABLE, null);
    }
    /** As {@link #plan(String)} for {@code kind}, with the group's stored desired_config (null on a first enable). */
    PlanBody plan(String profile, OperationKind kind, String desiredConfigJson) {
        PlanBody body = provider.createPlan(new PlanRequest(new GroupRef(
                com.xammer.cloud.domain.autospotting.Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                kind, new Profile(profile, null),
                new com.xammer.cloud.service.autospotting.spi.PlanOptions(null, desiredConfigJson, true)), aws.view());
        // The stored plan fingerprint carries the policy exactly when the plan's target owns it (AD-08).
        when(facts.planLiveFingerprint(planId)).thenReturn(Optional.of(AwsFingerprint.of(
                AwsGroupFacts.from(FakeAws.REGION, group()), AwsMaintenancePolicy.owns(body.targetJson()))));
        return body;
    }

    OperationContext context(OperationKind kind, String targetJson, String compensationJson) {
        op.setId(opId);
        op.setKind(kind);
        return new OperationContext(opId, kind, spotGroup(), targetJson, compensationJson,
                com.xammer.cloud.domain.autospotting.OperationPhase.FORWARD, planId, null);
    }

    OperationContext compensating(OperationKind kind, String compensationJson) {
        return new OperationContext(opId, kind, spotGroup(), null, compensationJson,
                com.xammer.cloud.domain.autospotting.OperationPhase.COMPENSATING, planId,
                com.xammer.cloud.domain.autospotting.CompensationTarget.PRIOR_CONFIG);
    }

    StepContext stepContext(int seq) {
        LeaseHandle handle = new LeaseHandle(LeaseHandle.Kind.GROUP, groupId.toString(), "tok", 1,
                Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(2), h -> true);
        return new StepContext(opId, seq, handle, null, Duration.ofSeconds(45));
    }

    JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of every method invoked on the write client, to prove which calls were made. */
    List<String> writeCalls() {
        List<String> out = new ArrayList<>();
        for (var inv : org.mockito.Mockito.mockingDetails(write).getInvocations()) {
            String n = inv.getMethod().getName();
            if (inv.getArguments().length == 1 && !(inv.getArguments()[0] instanceof java.util.function.Consumer)) {
                out.add(n);
            }
        }
        return out;
    }
}
