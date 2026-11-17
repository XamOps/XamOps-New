package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotGroupTransition;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.dto.autospotting.v2.GroupDetailDto;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupTransitionRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.aws.AwsGroupLister;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.list.GroupListCache;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.5: the v2 group list and detail endpoints (reads only). Driven at the controller
 * method with the tenancy guard mocked. A mocked SDK and no mutating path are covered by the AWS tests.
 */
class GroupControllerTest {

    private static final long ACCOUNT_ID = 7L;
    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final AwsGroupLister lister = mock(AwsGroupLister.class);
    private final GroupListCache cache = new GroupListCache(Duration.ofSeconds(60));
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final SpotGroupTransitionRepository transitions = mock(SpotGroupTransitionRepository.class);
    private final SpotOperationRepository operations = mock(SpotOperationRepository.class);
    private final GroupController controller = new GroupController(access, lister, cache, groups, transitions, operations);
    private final ClientUserDetails user = new ClientUserDetails("owner", "x",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 7L, 100L, "t");

    private CloudAccount account(String provider) {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        a.setProvider(provider);
        when(access.requireAccess(user, ACCOUNT_ID)).thenReturn(a);
        return a;
    }

    private static GroupListDto list(boolean partial) {
        GroupRowDto row = GroupRowDto.of(null, "AWS", "ap-south-1/web", "ap-south-1", "web", "UNMANAGED", false, false,
                4, 2, 8, "m5.large", new EligibilityResult(List.of(), List.of(), List.of()), null, true);
        return new GroupListDto(List.of(row), partial,
                partial ? List.of(new GroupListDto.FailedLocation("eu-west-1", "TIMEOUT")) : List.of(), Instant.now(),
                Map.of(), new GroupListDto.Kpis(1, 1, 0, null, 1));
    }

    @Test
    void listReturnsTheListNoStoreAndCachesACompleteOne() {
        CloudAccount a = account("AWS");
        when(lister.list(a)).thenReturn(list(false));
        ResponseEntity<GroupListDto> first = controller.list(ACCOUNT_ID, null, user);
        ResponseEntity<GroupListDto> second = controller.list(ACCOUNT_ID, null, user);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        assertThat(second.getBody()).isSameAs(first.getBody());
        verify(lister, times(1)).list(a);
        assertThat(first.getBody().items().get(0).savingsUnknown()).isTrue();
    }

    @Test
    void aPartialListIsNotCachedAndCarriesTheFailedLocations() {
        CloudAccount a = account("AWS");
        when(lister.list(a)).thenReturn(list(true));
        GroupListDto body = controller.list(ACCOUNT_ID, null, user).getBody();
        assertThat(body.partial()).isTrue();
        assertThat(body.failedLocations()).extracting(GroupListDto.FailedLocation::code).containsExactly("TIMEOUT");
        controller.list(ACCOUNT_ID, null, user);
        verify(lister, times(2)).list(a);
    }

    @Test
    void noCacheRefreshBypassesAndReplacesTheCache() {
        CloudAccount a = account("AWS");
        when(lister.list(a)).thenReturn(list(false));
        controller.list(ACCOUNT_ID, null, user);
        controller.list(ACCOUNT_ID, "no-cache", user);
        verify(lister, times(2)).list(a);
        controller.list(ACCOUNT_ID, null, user);
        verify(lister, times(2)).list(a);
    }

    @Test
    void anotherTenantsAccountIsRefusedByTheOwnershipGuard() {
        when(access.requireAccess(user, 99L)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> controller.list(99L, null, user)).isInstanceOf(AccessDeniedException.class);
        verify(lister, never()).list(any());
    }

    @Test
    void aNonAwsAccountIsNotReadyYet() {
        account("GCP");
        assertThatThrownBy(() -> controller.list(ACCOUNT_ID, null, user)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private SpotGroup row(UUID id, long accountId) {
        SpotGroup g = new SpotGroup();
        g.setId(id);
        g.setProvider(Provider.AWS);
        g.setCloudAccountId(accountId);
        g.setGroupKey("ap-south-1/web");
        g.setState(GroupState.ACTIVE);
        g.setConfigMode(ConfigMode.MANAGED);
        return g;
    }

    @Test
    void detailCarriesTheGroupItsTransitionsAndTheCurrentOperation() {
        CloudAccount a = account("AWS");
        UUID id = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        SpotGroup g = row(id, ACCOUNT_ID);
        g.setCurrentOperationId(opId);
        when(groups.findById(id)).thenReturn(Optional.of(g));
        GroupRowDto head = list(false).items().get(0);
        when(lister.row(eq(a), eq("ap-south-1/web"), eq(g))).thenReturn(Optional.of(head));
        SpotGroupTransition t = new SpotGroupTransition();
        t.setFromState(GroupState.PLANNED);
        t.setToState(GroupState.APPLYING);
        t.setReasonCode("CONFIRMED");
        t.setActorType("USER");
        t.setOccurredAt(Instant.parse("2026-11-17T10:00:00Z"));
        when(transitions.findByGroupIdOrderByIdAsc(id)).thenReturn(List.of(t));
        SpotOperation op = new SpotOperation();
        op.setId(opId);
        op.setGroupId(id);
        op.setKind(OperationKind.ENABLE);
        when(operations.findById(opId)).thenReturn(Optional.of(op));

        GroupDetailDto dto = controller.detail(ACCOUNT_ID, id, user).getBody();

        assertThat(dto.group()).isSameAs(head);
        assertThat(dto.configMode()).isEqualTo("MANAGED");
        assertThat(dto.transitions()).hasSize(1);
        assertThat(dto.transitions().get(0).reasonCode()).isEqualTo("CONFIRMED");
        assertThat(dto.transitions().get(0).toState()).isEqualTo("APPLYING");
        assertThat(dto.currentOperation().operationId()).isEqualTo(opId);
        assertThat(dto.currentOperation().kind()).isEqualTo("ENABLE");
    }

    @Test
    void aGroupOfAnotherAccountIs404NotALeak() {
        account("AWS");
        UUID id = UUID.randomUUID();
        when(groups.findById(id)).thenReturn(Optional.of(row(id, 999L)));
        assertThatThrownBy(() -> controller.detail(ACCOUNT_ID, id, user)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        when(groups.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.detail(ACCOUNT_ID, UUID.randomUUID(), user))
                .isInstanceOf(AutoSpottingApiException.class);
    }

    @Test
    void aGroupGoneFromTheCloudStillShowsFromTheSpine() {
        CloudAccount a = account("AWS");
        UUID id = UUID.randomUUID();
        SpotGroup g = row(id, ACCOUNT_ID);
        when(groups.findById(id)).thenReturn(Optional.of(g));
        when(lister.row(a, "ap-south-1/web", g)).thenReturn(Optional.empty());
        GroupDetailDto dto = controller.detail(ACCOUNT_ID, id, user).getBody();
        assertThat(dto.group().groupKey()).isEqualTo("ap-south-1/web");
        assertThat(dto.group().state()).isEqualTo("ACTIVE");
        assertThat(dto.group().savingsUnknown()).isFalse();
        assertThat(dto.currentOperation()).isNull();
    }

    @Test
    void aFailedLiveReadIsA502WithAStableCodeAndNoRawMessage() {
        CloudAccount a = account("AWS");
        UUID id = UUID.randomUUID();
        SpotGroup g = row(id, ACCOUNT_ID);
        when(groups.findById(id)).thenReturn(Optional.of(g));
        when(lister.row(a, "ap-south-1/web", g)).thenThrow(new RuntimeException("secret AccessKey AKIA..."));
        assertThatThrownBy(() -> controller.detail(ACCOUNT_ID, id, user)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getCode()).isEqualTo("CLOUD_READ_FAILED");
                    assertThat(e.getMessage()).doesNotContain("AKIA");
                });
    }

    private static GroupRowDto potentialRow(String name, String state, boolean managed, boolean external,
                                            EligibilityResult eligibility, Double savings) {
        return GroupRowDto.of(null, "AWS", "ap-south-1/" + name, "ap-south-1", name, state, managed, external,
                4, 2, 8, "m5.large", eligibility, savings, true);
    }
    @Test
    void potentialSumsPricedGroupsAndCountsUnpricedAndIneligibleWithoutAddingThem() {
        CloudAccount a = account("AWS");
        EligibilityResult ok = new EligibilityResult(List.of(), List.of(), List.of());
        EligibilityResult blocked = new EligibilityResult(
                List.of(EligibilityResult.Check.of("c1", "EC2_HEALTH_WITH_LB", "blocked")), List.of(), List.of());
        List<GroupRowDto> rows = List.of(
                potentialRow("managed", "ACTIVE", true, false, ok, 200.0),
                potentialRow("unmanaged-ok", "UNMANAGED", false, false, ok, 100.5),
                potentialRow("unmanaged-unpriced", "UNMANAGED", false, false, ok, null),
                potentialRow("unmanaged-blocked", "UNMANAGED", false, false, blocked, 999.0),
                potentialRow("external", "UNMANAGED", false, true, ok, 888.0));
        when(lister.list(a)).thenReturn(new GroupListDto(rows, false, List.of(), Instant.now(), Map.of(),
                new GroupListDto.Kpis(5, 3, 2, null, 1)));
        var dto = controller.potential(ACCOUNT_ID, user).getBody();
        assertThat(dto.potentialMonthlySavings()).isEqualTo(300.5);
        assertThat(dto.pricedGroups()).isEqualTo(2);
        assertThat(dto.unpricedGroups()).isEqualTo(1);
        assertThat(dto.ineligibleGroups()).isEqualTo(2);
        assertThat(dto.partial()).isFalse();
    }
    @Test
    void potentialIsNullNotZeroWhenNothingIsPriced() {
        CloudAccount a = account("AWS");
        EligibilityResult ok = new EligibilityResult(List.of(), List.of(), List.of());
        when(lister.list(a)).thenReturn(new GroupListDto(List.of(potentialRow("g", "UNMANAGED", false, false, ok, null)),
                false, List.of(), Instant.now(), Map.of(), new GroupListDto.Kpis(1, 1, 0, null, 1)));
        var dto = controller.potential(ACCOUNT_ID, user).getBody();
        assertThat(dto.potentialMonthlySavings()).isNull();
        assertThat(dto.unpricedGroups()).isEqualTo(1);
    }
}
