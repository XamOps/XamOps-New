package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.RiSpCoverageResponse;
import com.xammer.cloud.dto.autospotting.v2.CommitmentSettingsDto;
import com.xammer.cloud.dto.autospotting.v2.CommitmentSettingsRequest;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Role;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * plan-phase-14.md 14.1 (ux section 9.2 rows): the v2 Commitments endpoints, driven at the controller with
 * the tenancy guard and the PO-14 authorization mocked. The CSRF/intent header row is the
 * permissions-common filter (AutoSpottingIntentFilterTest covers this PUT path).
 */
class CommitmentsControllerTest {

    private static final long ACCOUNT_ID = 7L;

    private final CloudAccountAccessService access = mock(CloudAccountAccessService.class);
    private final AutoSpottingService service = mock(AutoSpottingService.class);
    private final AutoSpottingAuthorization authz = mock(AutoSpottingAuthorization.class);
    private final AutoSpottingAuditWriter audit = mock(AutoSpottingAuditWriter.class);
    private final CommitmentsController controller =
            new CommitmentsController(access, service, authz, audit, new IdempotencyService());
    private final ClientUserDetails user = new ClientUserDetails("owner", "x",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 7L, 100L, "t");

    /** The stored values, shared by the legacy-shaped getSettings and updateRispRespectSettings mocks. */
    private final Map<String, Object> stored = new HashMap<>(Map.of(
            "respectRiSpCommitments", false, "riSpUtilizationThreshold", 90));

    private CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        a.setProvider("AWS");
        Client c = new Client();
        c.setId(7L);
        a.setClient(c);
        when(access.requireAccess(user, ACCOUNT_ID)).thenReturn(a);
        when(service.getSettings(a)).thenAnswer(i -> new HashMap<>(stored));
        doAnswer(i -> {
            if (i.getArgument(1) != null) {
                stored.put("respectRiSpCommitments", i.getArgument(1));
            }
            if (i.getArgument(2) != null) {
                stored.put("riSpUtilizationThreshold", i.getArgument(2));
            }
            return null;
        }).when(service).updateRispRespectSettings(eq(a), any(), any());
        return a;
    }

    private void allow(CloudAccount a) {
        when(authz.authorizeConfirm(eq(user), eq(a), any(), any())).thenReturn(
                new Decision(true, Role.CLIENT_OWNER, Actor.user(100L, "ROLE_USER"), false, null, null, null));
    }

    @Test
    void ownerSavesAndTheValueRoundTripsAndEqualsTheLegacyGet() {
        CloudAccount a = account();
        allow(a);
        var put = controller.saveRispRespect(ACCOUNT_ID, "key-1", new CommitmentSettingsRequest(true, 75), user);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        CommitmentSettingsDto read = controller.settings(ACCOUNT_ID, user).getBody();
        assertThat(read).isEqualTo(new CommitmentSettingsDto(true, 75));
        Map<String, Object> legacy = service.getSettings(a);
        assertThat(read.respectRiSpCommitments()).isEqualTo(legacy.get("respectRiSpCommitments"));
        assertThat(read.riSpUtilizationThreshold()).isEqualTo(legacy.get("riSpUtilizationThreshold"));
    }

    @Test
    void theSaveWritesAnAuditRowWithBeforeAndAfter() {
        CloudAccount a = account();
        allow(a);
        controller.saveRispRespect(ACCOUNT_ID, "key-1", new CommitmentSettingsRequest(true, 75), user);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).write(captor.capture());
        AuditRecord r = captor.getValue();
        assertThat(r.action()).isEqualTo("RISP_RESPECT");
        assertThat(r.outcome()).isEqualTo("OK");
        assertThat(r.cloudAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(r.beforeState()).contains("\"respectRiSpCommitments\":false");
        assertThat(r.afterState()).contains("\"respectRiSpCommitments\":true").contains("75");
    }

    @Test
    void aSubUserPutIs403AndNothingIsSaved() {
        CloudAccount a = account();
        when(authz.authorizeConfirm(eq(user), eq(a), any(), any())).thenReturn(new Decision(false, Role.SUB_USER, null,
                false, "AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN, "You are not allowed to confirm this plan."));
        assertThatThrownBy(() -> controller.saveRispRespect(ACCOUNT_ID, "key-1",
                new CommitmentSettingsRequest(true, 75), user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("AS_FORBIDDEN_ROLE");
                });
        verify(service, never()).updateRispRespectSettings(any(), any(), anyInt());
        verify(audit, never()).write(any());
        assertThat(stored.get("respectRiSpCommitments")).isEqualTo(false);
    }

    @Test
    void anotherClientsAccountIsRefusedByTheOwnershipGuardOnReadAndWrite() {
        when(access.requireAccess(user, 99L)).thenThrow(new AccessDeniedException("no"));
        assertThatThrownBy(() -> controller.coverage(99L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.settings(99L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveRispRespect(99L, "k", new CommitmentSettingsRequest(true, 75), user))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aPutWithoutAnIdempotencyKeyIs400() {
        CloudAccount a = account();
        allow(a);
        assertThatThrownBy(() -> controller.saveRispRespect(ACCOUNT_ID, null,
                new CommitmentSettingsRequest(true, 75), user))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void coverageReturnsTheLegacyBody() {
        CloudAccount a = account();
        RiSpCoverageResponse body = new RiSpCoverageResponse();
        when(service.getRiSpCoverage(a, false)).thenReturn(body);
        assertThat(controller.coverage(ACCOUNT_ID, user).getBody()).isSameAs(body);
    }

    @Test
    void aFailedCoverageReadIsA502WithNoRawMessage() {
        CloudAccount a = account();
        when(service.getRiSpCoverage(a, false)).thenThrow(new RuntimeException("secret AccessKey AKIA..."));
        assertThatThrownBy(() -> controller.coverage(ACCOUNT_ID, user)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("AKIA");
                });
    }

    @Test
    void aGcpAccountIsNotReady() {
        CloudAccount a = account();
        a.setProvider("GCP");
        assertThatThrownBy(() -> controller.settings(ACCOUNT_ID, user)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
    }
}
