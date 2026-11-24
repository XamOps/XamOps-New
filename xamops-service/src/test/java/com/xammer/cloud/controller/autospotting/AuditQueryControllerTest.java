package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingAudit;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.AuditEntryDto;
import com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** plan-phase-14.md 14.2: the Activity read of autospotting_audit (limit at most 200, tenancy-checked). */
class AuditQueryControllerTest {

    private static final long ACCOUNT_ID = 7L;

    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final AutoSpottingAuditRepository audit = mock(AutoSpottingAuditRepository.class);
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private final AuditQueryController controller = new AuditQueryController(access, audit, groups, clock);
    private final ClientUserDetails user = new ClientUserDetails("owner", "x",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 7L, 100L, "t");

    private void account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        when(access.requireAccess(user, ACCOUNT_ID)).thenReturn(a);
    }

    private static AutoSpottingAudit row() {
        AutoSpottingAudit a = new AutoSpottingAudit();
        a.setId(1L);
        a.setOccurredAt(Instant.parse("2026-11-19T10:00:00Z"));
        a.setEventType("DRY_RUN");
        a.setActorType("USER");
        a.setActorUserId(100L);
        a.setAction("ENABLE");
        return a;
    }

    @Test
    void returnsTheAccountsRowsNewestFirstWithoutUserIds() {
        account();
        AutoSpottingAudit r = row();
        when(audit.findByCloudAccountIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                eq(ACCOUNT_ID), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(r)));
        List<AuditEntryDto> body = controller.audit(ACCOUNT_ID, null, null, null, null, user).getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).eventType()).isEqualTo("DRY_RUN");
        assertThat(body.get(0).toString()).doesNotContain("100");
    }

    @Test
    void theLimitDefaultsTo50AndIsCappedAt200() {
        account();
        when(audit.findByCloudAccountIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                eq(ACCOUNT_ID), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        controller.audit(ACCOUNT_ID, null, null, null, null, user);
        controller.audit(ACCOUNT_ID, null, null, null, 200, user);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(audit, org.mockito.Mockito.times(2))
                .findByCloudAccountIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        eq(ACCOUNT_ID), any(), any(), page.capture());
        assertThat(page.getAllValues()).extracting(Pageable::getPageSize).containsExactly(50, 200);
        assertThatThrownBy(() -> controller.audit(ACCOUNT_ID, null, null, null, 201, user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void aGroupOfAnotherAccountIs404AndNothingIsQueried() {
        account();
        UUID gid = UUID.randomUUID();
        SpotGroup other = new SpotGroup();
        other.setId(gid);
        other.setCloudAccountId(999L);
        when(groups.findById(gid)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> controller.audit(ACCOUNT_ID, gid, null, null, null, user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(audit, never()).findByCloudAccountIdAndGroupIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                any(), any(), any(), any(), any());
    }

    @Test
    void aGroupFilterUsesTheGroupQuery() {
        account();
        UUID gid = UUID.randomUUID();
        SpotGroup mine = new SpotGroup();
        mine.setId(gid);
        mine.setCloudAccountId(ACCOUNT_ID);
        when(groups.findById(gid)).thenReturn(Optional.of(mine));
        when(audit.findByCloudAccountIdAndGroupIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                eq(ACCOUNT_ID), eq(gid), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row())));
        assertThat(controller.audit(ACCOUNT_ID, gid, null, null, null, user).getBody()).hasSize(1);
    }

    @Test
    void anotherTenantsAccountIsRefused() {
        when(access.requireAccess(user, 99L)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> controller.audit(99L, null, null, null, null, user))
                .isInstanceOf(AccessDeniedException.class);
    }
}
