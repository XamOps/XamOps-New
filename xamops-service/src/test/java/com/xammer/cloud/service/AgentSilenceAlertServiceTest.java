package com.xammer.cloud.service;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.AgentSilenceAlertState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AgentHealthDto;
import com.xammer.cloud.repository.AccountObservabilityConfigRepository;
import com.xammer.cloud.repository.AgentSilenceAlertStateRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transition behaviour for agent-silence alerting.
 *
 * The point of the persisted state is that a host which has been down for a week generates
 * one notification, not one per scan — repeat alerts are how people learn to ignore them.
 */
class AgentSilenceAlertServiceTest {

    private static final long HOUR = 3_600_000L;

    private AgentHealthService agentHealthService;
    private AgentSilenceAlertStateRepository alertStateRepo;
    private EmailService emailService;
    private FinOpsReportScheduleRepository scheduleRepository;
    private AgentSilenceAlertService service;

    private AccountObservabilityConfig config;

    @BeforeEach
    void setUp() {
        agentHealthService = mock(AgentHealthService.class);
        alertStateRepo = mock(AgentSilenceAlertStateRepository.class);
        emailService = mock(EmailService.class);
        scheduleRepository = mock(FinOpsReportScheduleRepository.class);
        CloudAccountRepository cloudAccountRepo = mock(CloudAccountRepository.class);

        service = new AgentSilenceAlertService(
                mock(AccountObservabilityConfigRepository.class),
                alertStateRepo, cloudAccountRepo, scheduleRepository,
                agentHealthService, emailService);

        config = new AccountObservabilityConfig();
        config.setClientId(1L);
        config.setProviderAccountId("acct-1");
        config.setAccountName("Production");
        config.setO2OrgId("org-1");

        CloudAccount account = new CloudAccount();
        account.setId(99L);
        when(cloudAccountRepo.findByProviderAccountId("acct-1")).thenReturn(java.util.Optional.of(account));
        when(scheduleRepository.findActiveEmailsByAccountId(99L)).thenReturn(List.of("ops@example.com"));
        when(alertStateRepo.findByClientIdAndResolvedAtIsNull(1L)).thenReturn(List.of());
    }

    private AgentHealthDto agent(String host, AgentHealthDto.Status status, long ageMs) {
        return new AgentHealthDto(host, System.currentTimeMillis() - ageMs, status, ageMs, false);
    }

    @Test
    void opensAnAlertAndEmailsWhenAnAgentGoesSilent() {
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.OFFLINE, 2 * HOUR)));

        service.scanAccount(config);

        verify(alertStateRepo).save(any(AgentSilenceAlertState.class));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), subject.capture(), anyString());
        assertThat(subject.getValue()).contains("stopped reporting").contains("Production");
    }

    @Test
    void doesNotReNotifyWhileAnAlertIsAlreadyOpen() {
        AgentSilenceAlertState open = new AgentSilenceAlertState();
        open.setClientId(1L);
        open.setProviderAccountId("acct-1");
        open.setHostName("host-a");
        open.setStatus("OFFLINE");
        open.setAlertedAt(LocalDateTime.now().minusHours(3));
        when(alertStateRepo.findByClientIdAndResolvedAtIsNull(1L)).thenReturn(List.of(open));
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.OFFLINE, 3 * HOUR)));

        service.scanAccount(config);

        // A host down for hours must not email on every 30-minute scan.
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(alertStateRepo, never()).save(any(AgentSilenceAlertState.class));
    }

    @Test
    void resolvesAndNotifiesWhenAnAgentRecovers() {
        AgentSilenceAlertState open = new AgentSilenceAlertState();
        open.setClientId(1L);
        open.setProviderAccountId("acct-1");
        open.setHostName("host-a");
        open.setStatus("OFFLINE");
        open.setAlertedAt(LocalDateTime.now().minusHours(3));
        when(alertStateRepo.findByClientIdAndResolvedAtIsNull(1L)).thenReturn(List.of(open));
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.HEALTHY, 30_000)));

        service.scanAccount(config);

        assertThat(open.getResolvedAt()).isNotNull();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(anyString(), subject.capture(), anyString());
        assertThat(subject.getValue()).contains("restored");
    }

    @Test
    void staysSilentWhenEveryAgentIsHealthy() {
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.HEALTHY, 30_000)));

        service.scanAccount(config);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotAlertWhenNoAgentsAreKnown() {
        when(agentHealthService.listAgents(1L, "acct-1")).thenReturn(List.of());

        service.scanAccount(config);

        // An empty roster means observability was never set up for the account, not that
        // every agent died. Alerting here would be pure noise.
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(alertStateRepo, never()).save(any(AgentSilenceAlertState.class));
    }

    @Test
    void doesNotTreatDelayedAsAnOutage() {
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.DELAYED, 10 * 60_000)));

        service.scanAccount(config);

        // A few missed scrapes are not worth waking anyone for.
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void closesOpenAlertsForHostsThatLeftTheRoster() {
        AgentSilenceAlertState stale = new AgentSilenceAlertState();
        stale.setClientId(1L);
        stale.setProviderAccountId("acct-1");
        stale.setHostName("long-gone");
        stale.setStatus("GONE");
        stale.setAlertedAt(LocalDateTime.now().minusDays(30));
        when(alertStateRepo.findByClientIdAndResolvedAtIsNull(1L)).thenReturn(List.of(stale));
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("host-a", AgentHealthDto.Status.HEALTHY, 30_000)));

        service.scanAccount(config);

        // Otherwise the row can never resolve and blocks any future alert for that host.
        assertThat(stale.getResolvedAt()).isNotNull();
    }

    @Test
    void emailsEverySubscriberAndNamesTheAffectedHost() {
        when(scheduleRepository.findActiveEmailsByAccountId(99L))
                .thenReturn(List.of("a@example.com", "b@example.com"));
        when(agentHealthService.listAgents(1L, "acct-1"))
                .thenReturn(List.of(agent("web-01", AgentHealthDto.Status.OFFLINE, 2 * HOUR)));

        service.scanAccount(config);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(2)).sendHtmlEmail(anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("web-01");
    }

    @Test
    void callsOutABrokenPipelineDifferentlyFromADeadAgent() {
        AgentHealthDto brokenPipeline = new AgentHealthDto(
                "web-01", System.currentTimeMillis() - 2 * HOUR,
                AgentHealthDto.Status.OFFLINE, 2 * HOUR, true);

        String html = service.buildEmail("Production", List.of(brokenPipeline), List.of());

        // The fix differs: restart a dead agent, but reconfigure a live one that can't ship.
        assertThat(html).contains("agent process is alive");
    }

    @Test
    void escapesHostNamesInTheEmailBody() {
        AgentHealthDto hostile = new AgentHealthDto(
                "<script>alert(1)</script>", System.currentTimeMillis() - 2 * HOUR,
                AgentHealthDto.Status.OFFLINE, 2 * HOUR, false);

        String html = service.buildEmail("Production", List.of(hostile), List.of());

        // Host names arrive via telemetry, so they are untrusted input in an HTML email.
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void formatsSilenceDurationsReadably() {
        assertThat(AgentSilenceAlertService.formatAge(30 * 60_000L)).isEqualTo("30 min");
        assertThat(AgentSilenceAlertService.formatAge(HOUR)).isEqualTo("1 hour");
        assertThat(AgentSilenceAlertService.formatAge(5 * HOUR)).isEqualTo("5 hours");
        assertThat(AgentSilenceAlertService.formatAge(3 * 24 * HOUR)).isEqualTo("3 days");
        assertThat(AgentSilenceAlertService.formatAge(Long.MAX_VALUE)).isEqualTo("an unknown period");
    }
}
