package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.AnalyticsDto;
import com.xammer.cloud.repository.autospotting.SpotGroupEventRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** plan-phase-14.md 14.2: the analytics read. A metric with no source is null and named, never 0. */
class AnalyticsControllerTest {

    private static final long ACCOUNT_ID = 7L;

    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final SpotGroupEventRepository events = mock(SpotGroupEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private final AnalyticsController controller = new AnalyticsController(access, groups, events, clock);
    private final ClientUserDetails user = new ClientUserDetails("owner", "x",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 7L, 100L, "t");

    private final UUID g1 = UUID.randomUUID();

    private void account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        when(access.requireAccess(user, ACCOUNT_ID)).thenReturn(a);
        SpotGroup g = new SpotGroup();
        g.setId(g1);
        g.setCloudAccountId(ACCOUNT_ID);
        g.setGroupKey("ap-south-1/web");
        when(groups.findByCloudAccountId(ACCOUNT_ID)).thenReturn(List.of(g));
    }

    private static Object[] row(UUID g, String type, String instanceType, String lifecycle, String failure, long n) {
        return new Object[] {g, type, instanceType, lifecycle, failure, n};
    }

    @Test
    void withNoEventSourceEveryMetricIsNotTrackedAndNeverZero() {
        account();
        when(events.existsByGroupIdIn(any(Collection.class))).thenReturn(false);
        AnalyticsDto dto = controller.analytics(ACCOUNT_ID, null, null, user).getBody();
        assertThat(dto.notTracked()).containsExactlyInAnyOrder("spotLaunches", "launchFailures", "interruptions",
                "rebalanceRecommendations", "successRate");
        assertThat(dto.totals().spotLaunches()).isNull();
        assertThat(dto.totals().launchFailures()).isNull();
        assertThat(dto.totals().interruptions()).isNull();
        assertThat(dto.totals().rebalanceRecommendations()).isNull();
        assertThat(dto.totals().successRate()).isNull();
        assertThat(dto.groups()).isEmpty();
    }

    @Test
    void aTrackedAccountCountsPerGroupAndTypeWithClassifiedFailuresAndASuccessRate() {
        account();
        when(events.existsByGroupIdIn(any(Collection.class))).thenReturn(true);
        when(events.countByGroupAndType(any(Collection.class), any(), any())).thenReturn(List.of(
                row(g1, "LAUNCH", "m5.large", "spot", null, 9),
                row(g1, "LAUNCH", "m5.large", "on-demand", null, 4),
                row(g1, "LAUNCH_FAILED", "m5.large", null, "SPOT_CAPACITY", 1),
                row(g1, "INTERRUPTION_WARNING", "c5.large", null, null, 2),
                row(g1, "REBALANCE_RECOMMENDATION", "c5.large", null, null, 3)));
        AnalyticsDto dto = controller.analytics(ACCOUNT_ID, null, null, user).getBody();
        assertThat(dto.notTracked()).isEmpty();
        var f = dto.groups().get(0).figures();
        assertThat(f.spotLaunches()).isEqualTo(9);
        assertThat(f.launchFailures()).isEqualTo(1);
        assertThat(f.failuresByCause()).containsEntry("SPOT_CAPACITY", 1L);
        assertThat(f.interruptions()).isEqualTo(2);
        assertThat(f.rebalanceRecommendations()).isEqualTo(3);
        assertThat(f.successRate()).isEqualTo(90.0);
        assertThat(dto.groups().get(0).byType()).extracting(t -> t.instanceType()).containsExactly("c5.large", "m5.large");
        assertThat(dto.totals().spotLaunches()).isEqualTo(9);
    }

    @Test
    void trackedWithNothingInTheRangeIsZeroCountsAndNoRate() {
        account();
        when(events.existsByGroupIdIn(any(Collection.class))).thenReturn(true);
        when(events.countByGroupAndType(any(Collection.class), any(), any())).thenReturn(List.of());
        AnalyticsDto dto = controller.analytics(ACCOUNT_ID, null, null, user).getBody();
        assertThat(dto.notTracked()).isEmpty();
        assertThat(dto.totals().spotLaunches()).isZero();
        assertThat(dto.totals().successRate()).isNull();
    }

    @Test
    void theDefaultRangeIsThirtyUtcDaysEndingToday() {
        account();
        AnalyticsDto dto = controller.analytics(ACCOUNT_ID, null, null, user).getBody();
        assertThat(dto.from()).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));
        assertThat(dto.to()).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"));
    }

    @Test
    void aRangeOver92DaysOrBackwardsIs422() {
        account();
        assertThatThrownBy(() -> controller.analytics(ACCOUNT_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 20), user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> controller.analytics(ACCOUNT_ID, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 1), user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // Exactly 92 days is allowed.
        controller.analytics(ACCOUNT_ID, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 19), user);
    }

    @Test
    void anotherTenantsAccountIsRefused() {
        when(access.requireAccess(user, 99L)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> controller.analytics(99L, null, null, user)).isInstanceOf(AccessDeniedException.class);
    }
}
