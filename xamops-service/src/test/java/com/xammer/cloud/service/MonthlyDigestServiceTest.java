package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonthlyDigestServiceTest {

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinOpsService finOpsService;
    @Mock private AwsSecurityHubService awsSecurityHubService;
    @Mock private XamOpsRightsizingService xamOpsRightsizingService;
    @Mock private EbsDiskRightsizingService ebsDiskRightsizingService;
    @Mock private MonthlyDigestEmailBuilder emailBuilder;
    @Mock private EmailService emailService;

    private MonthlyDigestService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MonthlyDigestService(
                cloudAccountRepository, userRepository, finOpsService,
                awsSecurityHubService, xamOpsRightsizingService,
                ebsDiskRightsizingService, emailBuilder, emailService);
    }

    private CloudAccount awsAccount(String id, Long clientId) {
        CloudAccount a = mock(CloudAccount.class);
        Client client = mock(Client.class);
        when(a.getProvider()).thenReturn("AWS");
        when(a.getProviderAccountId()).thenReturn(id);
        when(a.getAccountName()).thenReturn("test-account");
        when(a.getClient()).thenReturn(client);
        when(client.getId()).thenReturn(clientId);
        return a;
    }

    private void stubAllDataSources(String accountId) {
        FinOpsReportDto report = mock(FinOpsReportDto.class);
        FinOpsReportDto.Kpis kpis = mock(FinOpsReportDto.Kpis.class);
        FinOpsReportDto.CostBreakdown breakdown = mock(FinOpsReportDto.CostBreakdown.class);
        when(report.getKpis()).thenReturn(kpis);
        when(kpis.getMonthToDateSpend()).thenReturn(100.0);
        when(kpis.getLastMonthSpend()).thenReturn(90.0);
        when(kpis.getPotentialSavings()).thenReturn(10.0);
        when(report.getCostBreakdown()).thenReturn(breakdown);
        when(breakdown.getByService()).thenReturn(List.of());
        when(report.getWastedResources()).thenReturn(List.of());
        when(finOpsService.getFinOpsReport(eq(accountId), eq(false), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(report));
        when(awsSecurityHubService.getSecurityFindings(eq(accountId), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(xamOpsRightsizingService.getLiveRecommendations(eq(accountId), eq(false)))
                .thenReturn(List.of());
        when(ebsDiskRightsizingService.getRecommendations(eq(accountId), eq(false)))
                .thenReturn(List.of());
        when(emailBuilder.build(any(), any(), any(), any(), any(), any()))
                .thenReturn("<html>digest</html>");
    }

    @Test
    void sendsEmailToAllUsersInClientAccount() {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        stubAllDataSources("123456789012");

        User u1 = mock(User.class); when(u1.getEmail()).thenReturn("alice@example.com");
        User u2 = mock(User.class); when(u2.getEmail()).thenReturn("bob@example.com");
        when(userRepository.findByClientId(1L)).thenReturn(List.of(u1, u2));

        service.runDigest();

        verify(emailService).sendHtmlEmail(eq("alice@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("bob@example.com"), anyString(), anyString());
    }

    @Test
    void skipsAccountWhenDataSourceThrows() {
        CloudAccount account = awsAccount("bad-account", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        when(finOpsService.getFinOpsReport(eq("bad-account"), eq(false), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("AWS error")));

        service.runDigest();

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void callsAllFourDataSourcesForAwsAccount() {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        stubAllDataSources("123456789012");
        when(userRepository.findByClientId(1L)).thenReturn(List.of());

        service.runDigest();

        verify(finOpsService).getFinOpsReport(eq("123456789012"), eq(false), anyString(), anyString());
        verify(awsSecurityHubService).getSecurityFindings(eq("123456789012"), eq(false));
        verify(xamOpsRightsizingService).getLiveRecommendations(eq("123456789012"), eq(false));
        verify(ebsDiskRightsizingService).getRecommendations(eq("123456789012"), eq(false));
    }

    @Test
    void skipsUsersWithoutEmail() {
        CloudAccount account = awsAccount("123456789012", 1L);
        when(cloudAccountRepository.findByProvider("AWS")).thenReturn(List.of(account));
        stubAllDataSources("123456789012");

        User noEmail = mock(User.class);
        when(noEmail.getEmail()).thenReturn(null);
        when(noEmail.getUsername()).thenReturn("not-an-email");
        User withEmail = mock(User.class);
        when(withEmail.getEmail()).thenReturn("valid@example.com");
        when(userRepository.findByClientId(1L)).thenReturn(List.of(noEmail, withEmail));

        service.runDigest();

        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("valid@example.com"), anyString(), anyString());
    }
}
