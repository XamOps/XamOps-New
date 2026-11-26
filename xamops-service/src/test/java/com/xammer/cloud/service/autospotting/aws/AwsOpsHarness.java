package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.autospotting.pg.OpsContexts;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A real PostgreSQL, the real operation runner, lease, store and lifecycle, and the real AWS provider
 * over the stubbed SDK (no AWS call, ever). One group row, one plan row and one claimed op, ready to
 * run. Shared by {@code AwsEnableEndToEndIT} and {@code AwsRolloutShortageIT}.
 */
final class AwsOpsHarness implements AutoCloseable {

    /** The real REVERT program, counting how often it was asked for a program. */
    static final class RecordingRevert implements AwsRevertProgramFactory {
        int calls;
        AwsRestoreProgram delegate;

        @Override
        public com.xammer.cloud.service.autospotting.spi.StepProgram program(
                com.xammer.cloud.service.autospotting.spi.OperationContext op,
                com.xammer.cloud.service.autospotting.spi.SnapshotPayload snapshot) {
            calls++;
            return delegate.program(op, snapshot);
        }

        @Override
        public PlanBody plan(com.xammer.cloud.service.autospotting.spi.PlanRequest request,
                             com.xammer.cloud.domain.CloudAccount account) {
            return delegate.plan(request, account);
        }

        @Override
        public boolean originalReadable(java.util.UUID groupId) {
            return delegate.originalReadable(groupId);
        }
    }

    final PgHarness.Database db;
    final AnnotationConfigApplicationContext ctx;
    final JdbcTemplate jdbc;
    final AwsConversionFixture f;
    final AwsSpotProvider provider;
    final RecordingRevert revert = new RecordingRevert();
    final OperationRunner runner;
    final OperationStore store;
    final LeaseHandle lease;
    final PlanBody plan;

    AwsOpsHarness(AutoScalingGroup group, OperationKind kind, GroupState groupState, GroupState priorState,
                  String profile) {
        this(group, kind, groupState, priorState, profile, true);
    }

    AwsOpsHarness(AutoScalingGroup group, OperationKind kind, GroupState groupState, GroupState priorState,
                  String profile, boolean mutationsOpen) {
        db = PgHarness.emptySchema();
        ctx = OpsContexts.contextFor(db);
        jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        f = new AwsConversionFixture(group, mutationsOpen);
        plan = f.plan(profile);
        store = ctx.getBean(OperationStore.class);
        insertGroup(groupState);
        insertPlan(kind);
        store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 7L, kind)
                .planId(f.planId)
                .priorState(priorState)
                .compensationTarget(CompensationTargets.forKind(kind))
                .targetConfigJson(plan.targetJson())
                .compensationConfigJson(CompensationTargets.forKind(kind)
                        == com.xammer.cloud.domain.autospotting.CompensationTarget.PRIOR_CONFIG
                        ? plan.currentJson() : null)
                .requestedBy("tester").build());
        store.claimForRun(f.opId);
        provider = f.providerWith(new com.xammer.cloud.service.autospotting.core.ops.OperationFacts(
                        ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotOperationRepository.class),
                        ctx.getBean(SpotOperationStepRepository.class)),
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), services -> {
                    revert.delegate = new AwsRestoreProgram(services, new com.xammer.cloud.service.autospotting.core
                            .GroupLookup(ctx.getBean(SpotGroupRepository.class)));
                    return revert;
                });
        runner = newRunner();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }

    /**
     * A REVERT ready to run: {@code live} is the converted group, {@code original} what it was before ENABLE
     * (stored as the group's linked snapshot), the group row is in {@code groupState} with {@code desired_config}
     * set, and the plan is the provider's own REVERT plan with every warning acknowledged at confirm.
     */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig) {
        this(live, original, groupState, desiredConfig, true);
    }

    /** As above; {@code claim=false} leaves the REVERT op QUEUED (an op it preempts must end first). */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig,
                  boolean claim) {
        this(live, original, groupState, desiredConfig, claim, x -> { });
    }

    /** As above; {@code beforePlan} stubs the fake AWS before the plan reads it (the plan reader caches the template). */
    AwsOpsHarness(AutoScalingGroup live, AutoScalingGroup original, GroupState groupState, String desiredConfig,
                  boolean claim, java.util.function.Consumer<AwsConversionFixture> beforePlan) {
        db = PgHarness.emptySchema();
        ctx = OpsContexts.contextFor(db);
        jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        f = new AwsConversionFixture(live, true);
        beforePlan.accept(f);
        store = ctx.getBean(OperationStore.class);
        insertGroup(groupState);
        jdbc.update("UPDATE spot_group SET desired_config = ?::jsonb WHERE id = ?", desiredConfig, f.groupId);
        UUID snapshotId = UUID.randomUUID();
        com.xammer.cloud.service.autospotting.spi.SnapshotPayload snap = AwsSnapshotCodec.encode(FakeAws.REGION, original,
                AwsSnapshotCodec.Context.none(), false);
        jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                        + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'AWS', ?, true, ?::jsonb, ?, ?, ?)",
                snapshotId, f.groupId, snap.schemaVersion(), snap.payloadJson(), snap.payloadSha256(),
                Timestamp.from(f.clock.instant()), UUID.randomUUID());
        jdbc.update("UPDATE spot_group SET snapshot_id = ? WHERE id = ?", snapshotId, f.groupId);
        provider = f.providerWith(new com.xammer.cloud.service.autospotting.core.ops.OperationFacts(
                        ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotOperationRepository.class),
                        ctx.getBean(SpotOperationStepRepository.class)),
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), services -> {
                    revert.delegate = new AwsRestoreProgram(services, new com.xammer.cloud.service.autospotting.core
                            .GroupLookup(ctx.getBean(SpotGroupRepository.class)));
                    return revert;
                });
        plan = provider.createPlan(new com.xammer.cloud.service.autospotting.spi.PlanRequest(
                new com.xammer.cloud.service.autospotting.spi.GroupRef(com.xammer.cloud.domain.autospotting.Provider.AWS,
                        7L, FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null), f.aws.view());
        insertRevertPlan();
        store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 7L, OperationKind.REVERT)
                .planId(f.planId).priorState(groupState)
                .compensationTarget(CompensationTargets.forKind(OperationKind.REVERT))
                .targetConfigJson(plan.targetJson()).requestedBy("tester").build());
        if (claim) {
            store.claimForRun(f.opId);
        }
        runner = newRunner();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }

    /**
     * A group that has fallback in play (plan-phase-20.md): the row in {@code state} with {@code config_mode},
     * {@code desired_config}, and for a PAUSED row the pause reason and {@code paused_from_state}. {@code kind} is a
     * FALLBACK_ENTER or FALLBACK_EXIT op ready to run (claimed), or null to leave the queue empty for the
     * reconciler's enqueue path. No plan and no snapshot: a system op has none (core §4.5).
     */
    AwsOpsHarness(AutoScalingGroup live, GroupState state, String configMode, String desiredConfig,
                  OperationKind kind, String pauseReason, GroupState pausedFrom) {
        db = PgHarness.emptySchema();
        ctx = OpsContexts.contextFor(db);
        jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
        f = new AwsConversionFixture(live, true);
        plan = null;
        store = ctx.getBean(OperationStore.class);
        insertGroup(state);
        jdbc.update("UPDATE spot_group SET config_mode = ?, desired_config = ?::jsonb, pause_reason = ?,"
                        + " paused_from_state = ?, fallback_fingerprint = CASE WHEN ? = 'FALLBACK' THEN 'fp-old' END"
                        + " WHERE id = ?", configMode, desiredConfig, pauseReason,
                pausedFrom == null ? null : pausedFrom.name(), configMode, f.groupId);
        provider = f.providerWith(new com.xammer.cloud.service.autospotting.core.ops.OperationFacts(
                        ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotOperationRepository.class),
                        ctx.getBean(SpotOperationStepRepository.class)),
                new AwsSnapshotStore(jdbc, ctx.getBean(FencedTx.class)), new AwsLaunchEventRecorder(jdbc), services -> {
                    revert.delegate = new AwsRestoreProgram(services, new com.xammer.cloud.service.autospotting.core
                            .GroupLookup(ctx.getBean(SpotGroupRepository.class)));
                    return revert;
                });
        if (kind != null) {
            store.insertQueued(OperationRecord.builder(f.opId, f.groupId, 7L, kind)
                    .priorState(state)
                    .priorPauseReason(pauseReason == null ? null
                            : com.xammer.cloud.domain.autospotting.PauseReason.valueOf(pauseReason))
                    .compensationTarget(CompensationTargets.forKind(kind))
                    .targetConfigJson(kind == OperationKind.FALLBACK_EXIT ? desiredConfig : null)
                    .requestedBy("system:fallback").build());
            store.claimForRun(f.opId);
        }
        runner = newRunner();
        lease = ctx.getBean(LeaseService.class).acquireGroup(f.groupId, LeaseService.Component.DISPATCHER).orElseThrow();
    }
    final UUID enableOpId = UUID.randomUUID();

    /** An ENABLE that already changed the group and waits for its rollout (WAITING, first mutation at step 3). */
    void startEnableInFlight(String targetJson) {
        startInFlight(OperationKind.ENABLE, targetJson);
    }
    /** As {@link #startEnableInFlight} for {@code kind} (ENABLE or ADOPT); the op id is {@link #enableOpId}. */
    void startInFlight(OperationKind kind, String targetJson) {
        jdbc.update("UPDATE spot_group SET state = 'APPLYING' WHERE id = ?", f.groupId);
        store.insertQueued(OperationRecord.builder(enableOpId, f.groupId, 7L, kind)
                .priorState(GroupState.UNMANAGED)
                .compensationTarget(com.xammer.cloud.domain.autospotting.CompensationTarget.SNAPSHOT)
                .targetConfigJson(targetJson).requestedBy("tester").build());
        store.claimForRun(enableOpId);
        store.park(enableOpId, com.xammer.cloud.domain.autospotting.WaitReason.CLOUD_PROGRESS,
                Instant.now().minusSeconds(1), 4, null);
        jdbc.update("UPDATE spot_operation SET first_mutation_seq = 3 WHERE id = ?", enableOpId);
    }

    /** Link {@code original} as the group's saved snapshot (what an ENABLE or ADOPT captured before it changed anything). */
    void linkSnapshot(AutoScalingGroup original) {
        UUID snapshotId = UUID.randomUUID();
        com.xammer.cloud.service.autospotting.spi.SnapshotPayload snap = AwsSnapshotCodec.encode(FakeAws.REGION, original,
                AwsSnapshotCodec.Context.none(), false);
        jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                        + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'AWS', ?, true, ?::jsonb, ?, ?, ?)",
                snapshotId, f.groupId, snap.schemaVersion(), snap.payloadJson(), snap.payloadSha256(),
                Timestamp.from(f.clock.instant()), UUID.randomUUID());
        jdbc.update("UPDATE spot_group SET snapshot_id = ? WHERE id = ?", snapshotId, f.groupId);
    }

    /**
     * Plan {@code request} with the real provider, store the plan OPEN the way PlanService does (body with the
     * eligibility blockers, every warning id, the live fingerprint for the target's policy ownership), then confirm
     * it through the real {@link com.xammer.cloud.service.autospotting.core.plan.ConfirmService}. Returns the new op id.
     */
    UUID planAndConfirm(com.xammer.cloud.service.autospotting.spi.PlanRequest request) {
        PlanBody p = provider.createPlan(request, f.aws.view());
        UUID planId = UUID.randomUUID();
        com.fasterxml.jackson.databind.node.ObjectNode body = AwsConversionFixture.JSON.createObjectNode();
        body.set("current", f.json(p.currentJson() == null ? "null" : p.currentJson()));
        body.set("target", f.json(p.targetJson() == null ? "null" : p.targetJson()));
        if (p.revertPathJson() != null) {
            body.set("revertPath", f.json(p.revertPathJson()));
        }
        var blockers = body.putObject("eligibility").putArray("blockers");
        p.eligibility().blockers().forEach(b -> blockers.addObject().put("code", b.code()).put("text", b.text()));
        StringBuilder warnings = new StringBuilder("[");
        List<String> warningIds = new java.util.ArrayList<>();
        for (var w : p.eligibility().warnings()) {
            warnings.append(warnings.length() > 1 ? "," : "").append('"').append(w.id()).append('"');
            warningIds.add(w.id());
        }
        warnings.append(']');
        Instant wall = Instant.now();
        Instant now = wall.isAfter(f.clock.instant()) ? wall : f.clock.instant();
        String from = groupState();
        boolean owns = AwsMaintenancePolicy.owns(p.targetJson() == null ? null : f.json(p.targetJson()));
        String fingerprint = AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()), owns);
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, dry_run,"
                        + " created_by, created_at, expires_at) VALUES (?, ?, 7, ?, 'OPEN', ?, 1, 1, ?, ?::jsonb,"
                        + " false, ?::jsonb, false, 'tester', ?, ?)",
                planId, f.groupId, request.kind().name(), from, fingerprint, body.toString(), warnings.toString(),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return confirmWith(planId, warningIds);
    }

    /** The persisted group row. */
    com.xammer.cloud.domain.autospotting.SpotGroup row() {
        return ctx.getBean(SpotGroupRepository.class).findById(f.groupId).orElseThrow();
    }

    /** The account the harness plans and confirms for (id 7, client 1). */
    static com.xammer.cloud.domain.CloudAccount harnessAccount() {
        com.xammer.cloud.domain.CloudAccount account = FakeAws.account();
        com.xammer.cloud.domain.Client client = new com.xammer.cloud.domain.Client();
        client.setId(1L);
        account.setClient(client);
        return account;
    }

    /**
     * A plan through the real {@link com.xammer.cloud.service.autospotting.core.plan.PlanService} (the kind/state
     * gate, NOT_MANAGED, AS_GROUP_BUSY, the provider plan, the stored body and fingerprint), as the API makes it.
     */
    com.xammer.cloud.domain.autospotting.SpotPlan planThroughService(OperationKind kind) {
        var service = new com.xammer.cloud.service.autospotting.core.plan.PlanService(ctx.getBean(SpotPlanRepository.class),
                ctx.getBean(SpotGroupRepository.class), ctx.getBean(SpotOperationRepository.class),
                new ProviderRegistry(List.of(provider)),
                org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.plan.FeeBasisResolver.class),
                org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions.class),
                ctx.getBean(SwitchService.class),
                org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties.class),
                ctx.getBean(GroupLifecycleService.class), java.time.Duration.ofMinutes(15));
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> service.createPlan(harnessAccount(), null,
                new com.xammer.cloud.service.autospotting.core.plan.PlanService.CreateCommand(kind, f.groupId, null,
                        "aggressive", false, null, "hash-" + UUID.randomUUID(), "tester")));
    }

    /** Confirm a stored plan through ConfirmService, acknowledging every warning it lists. Returns the op id. */
    UUID confirmPlan(UUID planId) {
        String ids = jdbc.queryForObject("SELECT warning_ids::text FROM spot_plan WHERE id = ?", String.class, planId);
        List<String> warningIds = new java.util.ArrayList<>();
        if (ids != null) {
            f.json(ids).forEach(n -> warningIds.add(n.asText()));
        }
        return confirmWith(planId, warningIds);
    }

    private UUID confirmWith(UUID planId, List<String> warningIds) {
        var authorization = org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.class);
        org.mockito.Mockito.when(authorization.authorizeConfirm(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision(true,
                        com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role.CLIENT_OWNER,
                        AutoSpottingAuditWriter.Actor.user(100L, "ROLE_USER"), false, null, null, null));
        var confirm = new com.xammer.cloud.service.autospotting.core.plan.ConfirmService(
                ctx.getBean(SpotPlanRepository.class), ctx.getBean(SpotGroupRepository.class),
                ctx.getBean(SpotOperationRepository.class), store,
                ctx.getBean(com.xammer.cloud.service.autospotting.core.ops.PreemptionService.class), authorization,
                new com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions(
                        org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties.class)),
                ctx.getBean(SwitchService.class),
                org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties.class),
                new ProviderRegistry(List.of(provider)), ctx.getBean(AutoSpottingAuditWriter.class),
                org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService.class));
        com.xammer.cloud.domain.CloudAccount account = harnessAccount();
        com.xammer.cloud.security.ClientUserDetails user = new com.xammer.cloud.security.ClientUserDetails("owner", "x",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")), 1L, 100L, "t");
        // ConfirmService carries @Transactional(REQUIRES_NEW) as a Spring bean; emulate that proxy here.
        return new org.springframework.transaction.support.TransactionTemplate(
                ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class))
                .execute(status -> confirm.confirm(account, user, planId,
                        new com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand(
                                jdbc.queryForObject("SELECT version FROM spot_plan WHERE id = ?", Integer.class, planId),
                                warningIds,
                                false, null, false, "k-" + planId, "h-" + planId))).operationId();
    }

    /** Claim {@code opId} and run it until it ends, moving the clock a minute between runs. */
    String runToEnd(UUID opId) {
        for (int round = 0; round < 60; round++) {
            String status = jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, opId);
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return status;
            }
            if (round > 0) {
                f.clock.advance(java.time.Duration.ofMinutes(1));
            }
            store.claimForRun(opId);
            runner.runClaimed(lease, opId);
        }
        return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, opId);
    }

    private OperationRunner newRunner() {
        return new OperationRunner(ctx.getBean(SpotOperationRepository.class), ctx.getBean(SpotGroupRepository.class),
                store, ctx.getBean(StepExecutor.class), ctx.getBean(GroupLifecycleService.class),
                ctx.getBean(FencedTx.class), ctx.getBean(AutoSpottingAuditWriter.class),
                ctx.getBean(SwitchService.class), ctx.getBean(SpotOpsLifecycle.class),
                new ProviderRegistry(List.of(provider)),
                ctx.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class));
    }

    /** The REVERT plan row: the live fingerprint now, the provider's body, and every warning acknowledged. */
    private void insertRevertPlan() {
        Instant now = f.clock.instant();
        // As PlanService stores it: ownership from the plan's target (liveFingerprintFor).
        String fingerprint = AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()),
                AwsMaintenancePolicy.owns(plan.targetJson()));
        String body = "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson()
                + ",\"revertPath\":" + plan.revertPathJson() + "}";
        StringBuilder acks = new StringBuilder("[");
        for (var w : plan.eligibility().warnings()) {
            acks.append(acks.length() > 1 ? "," : "").append('"').append(w.id()).append('"');
        }
        acks.append(']');
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, confirm_acks,"
                        + " dry_run, created_by, created_at, expires_at) VALUES (?, ?, 7, 'REVERT', 'CONFIRMED',"
                        + " 'ACTIVE', 1, 1, ?, ?::jsonb, false, '[]'::jsonb, ?::jsonb, false, 'test', ?, ?)",
                f.planId, f.groupId, fingerprint, body, acks.toString(), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
    }

    private void insertGroup(GroupState state) {
        Instant now = f.clock.instant();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.groupId);
        row.put("provider", "AWS");
        row.put("cloud_account_id", 7L);
        row.put("client_id", 1L);
        row.put("group_key", FakeAws.REGION + "/web-asg");
        row.put("state", state.name());
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
    }

    private void insertPlan(OperationKind kind) {
        Instant now = f.clock.instant();
        // The plan's fingerprint includes the maintenance policy exactly when its target owns it (AD-08).
        String fingerprint = AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()),
                AwsMaintenancePolicy.owns(plan.targetJson()));
        String body = "{\"current\":" + plan.currentJson() + ",\"target\":" + plan.targetJson() + "}";
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                        + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, dry_run,"
                        + " created_by, created_at, expires_at) VALUES (?, ?, 7, ?, 'CONFIRMED', 'UNMANAGED', 1, 1,"
                        + " ?, ?::jsonb, false, '[]'::jsonb, false, 'test', ?, ?)",
                f.planId, f.groupId, kind.name(), fingerprint, body, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(3600)));
    }

    void engageSwitch(String scopeKey) {
        jdbc.update("INSERT INTO autospotting_switch (scope_key, engaged, reason, changed_by, changed_at, version)"
                + " VALUES (?, true, 'test', 'tester', ?, 0)", scopeKey, Timestamp.from(f.clock.instant()));
    }

    /** Claim the op (QUEUED or WAITING) and run it to its next boundary. */
    void claimAndRun() {
        store.claimForRun(f.opId);
        runner.runClaimed(lease, f.opId);
    }

    String opStatus() {
        return jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opPhase() {
        return jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opWaitReason() {
        return jdbc.queryForObject("SELECT wait_reason FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String opError() {
        return jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, f.opId);
    }

    String groupState() {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, f.groupId);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Override
    public void close() {
        ctx.close();
        db.close();
    }
}
