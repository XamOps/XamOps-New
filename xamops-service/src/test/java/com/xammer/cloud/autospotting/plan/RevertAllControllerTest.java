package com.xammer.cloud.autospotting.plan;
import com.xammer.cloud.controller.autospotting.RevertAllController;
import com.xammer.cloud.controller.autospotting.RevertAllController.BatchConfirmRequest;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmResult;
import com.xammer.cloud.service.autospotting.core.plan.PlanService;
import com.xammer.cloud.service.autospotting.core.plan.PlanService.CreateCommand;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService;
import com.xammer.cloud.service.autospotting.core.plan.RevertAllService.Batch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/** "Revert all" (PO-21, design-core.md section 7.5): one REVERT plan per revertable group, batch confirm. */
class RevertAllControllerTest {
    private static final long ACCOUNT_ID = 9L;
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final SpotPlanRepository plans = mock(SpotPlanRepository.class);
    private final SpotOperationRepository operations = mock(SpotOperationRepository.class);
    private final PlanService planService = mock(PlanService.class);
    private final ConfirmService confirmService = mock(ConfirmService.class);
    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final IdempotencyService idempotency = new IdempotencyService();
    private final Map<UUID, SpotPlan> created = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> batchOf = new ConcurrentHashMap<>();
    private RevertAllController controller;
    private CloudAccount account;
    private ClientUserDetails user;
    @BeforeEach
    void setUp() {
        account = new CloudAccount();
        account.setId(ACCOUNT_ID);
        user = new ClientUserDetails("owner", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")), 1L, 100L, "t");
        when(access.requireAccess(user, ACCOUNT_ID)).thenReturn(account);
        when(planService.createPlan(any(), any(), any(CreateCommand.class))).thenAnswer(inv -> {
            CreateCommand cmd = inv.getArgument(2);
            SpotPlan p = new SpotPlan();
            p.setId(UUID.randomUUID());
            p.setGroupId(cmd.groupId());
            p.setCloudAccountId(ACCOUNT_ID);
            p.setKind(OperationKind.REVERT);
            p.setStatus(SpotPlan.PlanStatus.OPEN);
            p.setVersion(1);
            p.setBody("{}");
            p.setWarningIds("[]");
            p.setCreatedBy("owner");
            p.setCreatedAt(Instant.now());
            p.setExpiresAt(Instant.now().plusSeconds(600));
            created.put(p.getId(), p);
            return p;
        });
        when(plans.assignBatch(any(), any())).thenAnswer(inv -> {
            batchOf.put(inv.getArgument(0), inv.getArgument(1));
            return 1;
        });
        when(plans.findByBatchId(any())).thenAnswer(inv -> created.values().stream()
                .filter(p -> inv.getArgument(0).equals(batchOf.get(p.getId()))).toList());
        when(operations.findByPlanId(any())).thenReturn(java.util.Optional.empty());
        controller = new RevertAllController(access,
                new RevertAllService(groups, plans, operations, planService, confirmService, idempotency),
                new AccountDeletionGuard(groups), idempotency);
    }
    private SpotGroup group(String key, GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setGroupKey(key);
        g.setState(state);
        when(groups.findById(g.getId())).thenReturn(java.util.Optional.of(g));
        return g;
    }
    private void rows(SpotGroup... rows) {
        when(groups.findByCloudAccountId(ACCOUNT_ID)).thenReturn(new ArrayList<>(List.of(rows)));
    }
    @Test
    void createsOneRevertPlanPerRevertableGroupSharingOneBatchId() {
        SpotGroup active = group("us-east-1/a", GroupState.ACTIVE);
        SpotGroup failed = group("us-east-1/b", GroupState.FAILED);
        SpotGroup degraded = group("us-east-1/c", GroupState.DEGRADED);
        SpotGroup reverted = group("us-east-1/d", GroupState.REVERTED);
        SpotGroup unmanaged = group("us-east-1/e", GroupState.UNMANAGED);
        SpotGroup reverting = group("us-east-1/f", GroupState.REVERTING);
        rows(active, failed, degraded, reverted, unmanaged, reverting);
        ResponseEntity<Batch> res = controller.create(ACCOUNT_ID, "key-1", user);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Batch batch = res.getBody();
        assertThat(batch.total()).isEqualTo(3);
        assertThat(batch.items()).extracting("groupKey").containsExactly("us-east-1/a", "us-east-1/b", "us-east-1/c");
        assertThat(batch.alreadyReverting()).containsExactly(reverting.getId());
        ArgumentCaptor<CreateCommand> cmds = ArgumentCaptor.forClass(CreateCommand.class);
        verify(planService, org.mockito.Mockito.times(3)).createPlan(eq(account), eq(user), cmds.capture());
        assertThat(cmds.getAllValues()).allSatisfy(c -> assertThat(c.kind()).isEqualTo(OperationKind.REVERT));
        assertThat(batchOf.values()).hasSize(3).containsOnly(batch.batchId());
    }
    @Test
    void sameIdempotencyKeyMapsToTheSameBatchId() {
        rows(group("us-east-1/a", GroupState.ACTIVE));
        UUID first = controller.create(ACCOUNT_ID, "key-1", user).getBody().batchId();
        UUID again = controller.create(ACCOUNT_ID, "key-1", user).getBody().batchId();
        UUID other = controller.create(ACCOUNT_ID, "key-2", user).getBody().batchId();
        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }
    @Test
    void missingIdempotencyKeyIs400() {
        assertThatThrownBy(() -> controller.create(ACCOUNT_ID, " ", user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(400));
        verify(planService, never()).createPlan(any(), any(), any());
    }
    @Test
    void oneFailedPlanCreationDoesNotStopTheOthers() {
        SpotGroup a = group("us-east-1/a", GroupState.ACTIVE);
        SpotGroup b = group("us-east-1/b", GroupState.ACTIVE);
        rows(a, b);
        when(planService.createPlan(any(), any(), any(CreateCommand.class))).thenAnswer(inv -> {
            CreateCommand cmd = inv.getArgument(2);
            if (cmd.groupId().equals(a.getId())) {
                throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_GROUP_BUSY", "busy");
            }
            SpotPlan p = new SpotPlan();
            p.setId(UUID.randomUUID());
            p.setGroupId(cmd.groupId());
            p.setCloudAccountId(ACCOUNT_ID);
            p.setVersion(1);
            p.setCreatedAt(Instant.now());
            created.put(p.getId(), p);
            return p;
        });
        Batch batch = controller.create(ACCOUNT_ID, "k", user).getBody();
        assertThat(batch.total()).isEqualTo(2);
        assertThat(batch.failed()).isEqualTo(1);
        assertThat(batch.items().get(0).errorCode()).isEqualTo("AS_GROUP_BUSY");
        assertThat(batch.items().get(1).plan()).isNotNull();
    }
    @Test
    void batchConfirmQueuesOneOperationEachAndOneFailureDoesNotStopTheRest() {
        SpotGroup a = group("us-east-1/a", GroupState.ACTIVE);
        SpotGroup b = group("us-east-1/b", GroupState.FAILED);
        SpotGroup c = group("us-east-1/c", GroupState.PAUSED);
        rows(a, b, c);
        Batch batch = controller.create(ACCOUNT_ID, "k", user).getBody();
        UUID opA = UUID.randomUUID();
        UUID opC = UUID.randomUUID();
        when(confirmService.confirm(any(), any(), any(), any(ConfirmCommand.class))).thenAnswer(inv -> {
            UUID planId = inv.getArgument(2);
            UUID groupId = created.get(planId).getGroupId();
            if (groupId.equals(b.getId())) {
                throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_GROUP_BUSY", "busy");
            }
            return new ConfirmResult(groupId.equals(a.getId()) ? opA : opC, "QUEUED");
        });
        ResponseEntity<Batch> res = controller.confirm(ACCOUNT_ID, batch.batchId(), "ck",
                new BatchConfirmRequest(List.of(), null), user);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(confirmService, org.mockito.Mockito.times(3)).confirm(any(), any(), any(), any(ConfirmCommand.class));
        List<RevertAllService.Item> items = res.getBody().items();
        assertThat(items.get(0).operationId()).isEqualTo(opA);
        assertThat(items.get(1).errorCode()).isEqualTo("AS_GROUP_BUSY");
        assertThat(items.get(1).operationId()).isNull();
        assertThat(items.get(2).operationId()).isEqualTo(opC);
    }
    @Test
    void confirmOfAnUnknownBatchIs404() {
        assertThatThrownBy(() -> controller.confirm(ACCOUNT_ID, UUID.randomUUID(), "ck",
                new BatchConfirmRequest(List.of(), null), user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
    @Test
    void batchViewCountsRevertedGroups() {
        SpotGroup a = group("us-east-1/a", GroupState.ACTIVE);
        SpotGroup b = group("us-east-1/b", GroupState.ACTIVE);
        rows(a, b);
        Batch batch = controller.create(ACCOUNT_ID, "k", user).getBody();
        a.setState(GroupState.REVERTED);
        Batch view = controller.get(ACCOUNT_ID, batch.batchId(), user).getBody();
        assertThat(view.total()).isEqualTo(2);
        assertThat(view.reverted()).isEqualTo(1);
    }
    @Test
    void deletionCheckListsBlockingAndStillConvertedGroups() {
        rows(group("us-east-1/a", GroupState.ACTIVE), group("us-east-1/b", GroupState.DEGRADED));
        AccountDeletionGuard.Check check = controller.deletionCheck(ACCOUNT_ID, user).getBody();
        assertThat(check.deletable()).isFalse();
        assertThat(check.blocking()).extracting("groupKey").containsExactly("us-east-1/a");
        assertThat(check.stillConverted()).extracting("groupKey").containsExactly("us-east-1/b");
    }
}
