package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskAlertState;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.messaging.MessageKind;
import com.xammer.cloud.messaging.MobileNotificationDispatcher;
import com.xammer.cloud.messaging.NotificationChannel;
import com.xammer.cloud.repository.DiskAlertStateRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiskAlertServiceTest {

    @Mock private FinOpsReportScheduleRepository scheduleRepository;
    @Mock private EbsDiskRightsizingService ebsDiskRightsizingService;
    @Mock private DiskAlertStateRepository diskAlertStateRepository;
    @Mock private AlertEmailBuilder alertEmailBuilder;
    @Mock private EmailService emailService;
    @Mock private AlertTextBuilder alertTextBuilder;
    @Mock private MobileNotificationDispatcher mobileNotificationDispatcher;

    private DiskAlertService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DiskAlertService(
                scheduleRepository, ebsDiskRightsizingService,
                diskAlertStateRepository, alertEmailBuilder, emailService,
                alertTextBuilder, mobileNotificationDispatcher);
        // Default to inactive so the existing email-only assertions below are unaffected;
        // the mobile path is exercised in its own tests.
        lenient().when(mobileNotificationDispatcher.isActive()).thenReturn(false);
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private CloudAccount awsAccount(Long id, String providerAccountId) {
        CloudAccount acc = new CloudAccount();
        acc.setId(id);
        acc.setProvider("AWS");
        acc.setAccountName("Test Account");
        // CloudAccount.getProviderAccountId() uses switch on provider field
        acc.setAwsAccountId(providerAccountId);
        return acc;
    }

    private Map<String, Object> volume(String volumeId, String region, int sizeGb, double usedPercent) {
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", volumeId);
        vol.put("region", region);
        vol.put("sizeGb", sizeGb);
        vol.put("usedPercent", usedPercent);
        vol.put("freeGb", sizeGb * (1 - usedPercent / 100.0));
        vol.put("recommendation", usedPercent > 80 ? "EXPAND" : (usedPercent < 25 ? "SHRINK" : "NONE"));
        return vol;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void runAlertScan_skipsNonAwsAccounts() {
        CloudAccount gcpAccount = new CloudAccount();
        gcpAccount.setProvider("GCP");
        when(scheduleRepository.findDistinctActiveAccounts()).thenReturn(List.of(gcpAccount));

        service.runAlertScan();

        verify(ebsDiskRightsizingService, never()).getVolumes(any(), anyBoolean());
    }

    @Test
    void scanAccount_firesAlertAndPersistsStateForNewNearFullVolume() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0); // 90% used

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), eq("Test Account"), eq("123456789012")))
                .thenReturn("<html>alert</html>");

        service.scanAccount(account);

        // Email sent
        verify(emailService).sendHtmlEmail(eq("ops@example.com"), contains("Disk Alert"), anyString());

        // State persisted
        ArgumentCaptor<DiskAlertState> captor = ArgumentCaptor.forClass(DiskAlertState.class);
        verify(diskAlertStateRepository).save(captor.capture());
        DiskAlertState saved = captor.getValue();
        assertThat(saved.getAlertType()).isEqualTo("NEAR_FULL");
        assertThat(saved.getVolumeId()).isEqualTo("vol-001");
        assertThat(saved.getResolvedAt()).isNull();
    }

    @Test
    void scanAccount_doesNotRepeatAlertWhenActiveStateExists() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        DiskAlertState existing = new DiskAlertState();
        existing.setAlertType("NEAR_FULL");
        existing.setVolumeId("vol-001");

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of(existing));

        service.scanAccount(account);

        verify(emailService, never()).sendHtmlEmail(any(), any(), any());
        verify(diskAlertStateRepository, never()).save(any());
    }

    @Test
    void scanAccount_setsResolvedAtWhenVolumeRecoversToBelowThreshold() {
        CloudAccount account = awsAccount(1L, "123456789012");
        // Volume now at 60% — healthy (was previously alerted for NEAR_FULL)
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 60.0);

        DiskAlertState active = new DiskAlertState();
        active.setAlertType("NEAR_FULL");
        active.setVolumeId("vol-001");
        active.setAlertedAt(Instant.now().minusSeconds(3600));

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of(active));

        service.scanAccount(account);

        // resolvedAt set on the existing state
        ArgumentCaptor<DiskAlertState> captor = ArgumentCaptor.forClass(DiskAlertState.class);
        verify(diskAlertStateRepository).save(captor.capture());
        assertThat(captor.getValue().getResolvedAt()).isNotNull();

        // No new alert email sent
        verify(emailService, never()).sendHtmlEmail(any(), any(), any());
    }

    @Test
    void scanAccount_firesAlertForUnderutilizedVolume() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-002", "ap-south-1", 50, 10.0); // 10% used, 50GB >= 20

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-002", "UNDERUTILIZED"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        service.scanAccount(account);

        ArgumentCaptor<DiskAlertState> captor = ArgumentCaptor.forClass(DiskAlertState.class);
        verify(diskAlertStateRepository).save(captor.capture());
        assertThat(captor.getValue().getAlertType()).isEqualTo("UNDERUTILIZED");
    }

    @Test
    void scanAccount_alsoDispatchesAlertToMobileChannelsWhenActive() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(true, true)));
        when(scheduleRepository.findActiveContactsByAccountId(1L)).thenReturn(List.of("9876543210"));
        when(alertTextBuilder.buildAlertVariables(anyList(), any())).thenReturn(List.of("Test Account", "1", "1"));

        service.scanAccount(account);

        verify(mobileNotificationDispatcher).dispatchAll(
                eq(List.of("9876543210")), anyList(), eq(MessageKind.ALERT), anyString(),
                eq(EnumSet.of(NotificationChannel.SMS, NotificationChannel.WHATSAPP)));
    }

    @Test
    void scanAccount_passesOnlyTheChannelsTheClientIsEntitledTo() {
        // WhatsApp granted, SMS not. Each send is billed, so an ungranted channel must not fire.
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(false, true)));
        when(scheduleRepository.findActiveContactsByAccountId(1L)).thenReturn(List.of("9876543210"));
        when(alertTextBuilder.buildAlertVariables(anyList(), any())).thenReturn(List.of("Test Account", "1", "1"));

        service.scanAccount(account);

        verify(mobileNotificationDispatcher).dispatchAll(
                anyList(), anyList(), eq(MessageKind.ALERT), anyString(),
                eq(EnumSet.of(NotificationChannel.WHATSAPP)));
    }

    @Test
    void scanAccount_sendsNoMobileAlertAndSkipsTheContactQueryWhenClientIsNotEntitled() {
        // The default for every client. Email still goes out; nothing chargeable happens, and the
        // recipient query is not even run.
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(false, false)));

        service.scanAccount(account);

        verify(emailService).sendHtmlEmail(eq("ops@example.com"), contains("Disk Alert"), anyString());
        verify(scheduleRepository, never()).findActiveContactsByAccountId(any());
        verify(mobileNotificationDispatcher, never())
                .dispatchAll(any(), anyList(), any(), anyString(), any());
    }

    /** A client row carrying only the two notification entitlement flags. */
    private Client entitledClient(boolean sms, boolean whatsapp) {
        Client client = new Client();
        client.setSmsNotificationsEnabled(sms);
        client.setWhatsappNotificationsEnabled(whatsapp);
        return client;
    }

    @Test
    void scanAccount_doesNotQueryContactsWhenMobileChannelsAreInactive() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        service.scanAccount(account);

        verify(scheduleRepository, never()).findActiveContactsByAccountId(any());
        verify(mobileNotificationDispatcher, never())
                .dispatchAll(any(), anyList(), any(), anyString(), any());
    }

    @Test
    void scanAccount_emailStillSentWhenMobileDispatchBlowsUp() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-001", "ap-south-1", 30, 90.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-001", "NEAR_FULL"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        // Entitled, so the mobile path actually reaches the failing query. Without this the
        // entitlement check returns early and the test passes without exercising the failure.
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(false, true)));
        when(scheduleRepository.findActiveContactsByAccountId(1L))
                .thenThrow(new RuntimeException("contact query exploded"));

        service.scanAccount(account);

        // Email is the channel of record — a mobile failure must not take it down with it.
        verify(emailService).sendHtmlEmail(eq("ops@example.com"), contains("Disk Alert"), anyString());
        verify(scheduleRepository).findActiveContactsByAccountId(1L);
    }

    // ── critical-only mobile delivery ────────────────────────────────────────

    @Test
    void scanAccount_sendsNoMobileAlertForAWarningOnlyScan() {
        // An over-provisioned disk is a cost tip, not an emergency: email only.
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = volume("vol-002", "ap-south-1", 50, 10.0); // underutilized → WARNING

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(1L, "vol-002", "UNDERUTILIZED"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(true, true)));

        service.scanAccount(account);

        verify(emailService).sendHtmlEmail(eq("ops@example.com"), contains("Disk Alert"), anyString());
        // Never reaches the dispatcher — so it cannot use up the 24h cooldown and silence a real
        // critical alert later that day. Not even the client lookup runs.
        verify(mobileNotificationDispatcher, never())
                .dispatchAll(any(), anyList(), any(), anyString(), any());
        verify(scheduleRepository, never()).findClientByAccountId(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scanAccount_mobileAlertCarriesOnlyTheCriticalSubsetOfAMixedScan() {
        // One disk near full, one over-provisioned, both new in the same scan. Email gets both;
        // the phone message must count only the critical one.
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> full = volume("vol-001", "ap-south-1", 30, 90.0);
        Map<String, Object> idle = volume("vol-002", "ap-south-1", 50, 10.0);

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(full, idle));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());
        when(diskAlertStateRepository.findByAccount_IdAndVolumeIdAndAlertType(eq(1L), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveEmailsByAccountId(1L)).thenReturn(List.of("ops@example.com"));
        when(alertEmailBuilder.buildAlertDigestEmail(anyList(), any(), any())).thenReturn("<html>alert</html>");

        when(mobileNotificationDispatcher.isActive()).thenReturn(true);
        when(scheduleRepository.findClientByAccountId(1L))
                .thenReturn(Optional.of(entitledClient(false, true)));
        when(scheduleRepository.findActiveContactsByAccountId(1L)).thenReturn(List.of("9876543210"));
        when(alertTextBuilder.buildAlertVariables(anyList(), any())).thenReturn(List.of("Test Account", "1", "1"));

        service.scanAccount(account);

        ArgumentCaptor<List<AlertDto>> emailed = ArgumentCaptor.forClass(List.class);
        verify(alertEmailBuilder).buildAlertDigestEmail(emailed.capture(), any(), any());
        assertThat(emailed.getValue()).hasSize(2);

        ArgumentCaptor<List<AlertDto>> mobile = ArgumentCaptor.forClass(List.class);
        verify(alertTextBuilder).buildAlertVariables(mobile.capture(), any());
        assertThat(mobile.getValue())
                .extracting(AlertDto::getId, AlertDto::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vol-001", "CRITICAL"));

        verify(mobileNotificationDispatcher).dispatchAll(
                anyList(), anyList(), eq(MessageKind.ALERT), anyString(),
                eq(EnumSet.of(NotificationChannel.WHATSAPP)));
    }

    @Test
    void scanAccount_skipsVolumesWithoutAgentData() {
        CloudAccount account = awsAccount(1L, "123456789012");
        Map<String, Object> vol = new HashMap<>();
        vol.put("volumeId", "vol-003");
        vol.put("region", "ap-south-1");
        vol.put("sizeGb", 30);
        // No "usedPercent" key — no agent data

        when(ebsDiskRightsizingService.getVolumes("123456789012", false)).thenReturn(List.of(vol));
        when(diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(1L)).thenReturn(List.of());

        service.scanAccount(account);

        verify(emailService, never()).sendHtmlEmail(any(), any(), any());
    }
}
