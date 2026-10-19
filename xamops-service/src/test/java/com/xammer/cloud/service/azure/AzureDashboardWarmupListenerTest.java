package com.xammer.cloud.service.azure;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The onboarding warmup's job: a newly connected Azure subscription must not sit on empty Waste /
 * Rightsizing / Security / Alerts cards until the next scheduled scan. So after the first snapshot
 * it force-refreshes each of those caches, and one failing scan must not stop the others.
 */
class AzureDashboardWarmupListenerTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final String SUB = "sub-xyz";
    private static final String TENANT = "tenant-1";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AzureDashboardService azureDashboardService;
    @Mock private AzureWasteService azureWasteService;
    @Mock private AzureRightsizingService azureRightsizingService;
    @Mock private AzureSecurityService azureSecurityService;
    @Mock private AzureAlertsService azureAlertsService;
    @Mock private SchedulingEnvironmentConfig envConfig;

    private AzureDashboardWarmupListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new AzureDashboardWarmupListener(cloudAccountRepository, azureDashboardService,
                azureWasteService, azureRightsizingService, azureSecurityService, azureAlertsService, envConfig);

        CloudAccount account = new CloudAccount();
        account.setAzureSubscriptionId(SUB);
        account.setStatus("CONNECTED");
        when(cloudAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    @Test
    @DisplayName("warmup force-refreshes waste, rightsizing, security and alerts exactly once each")
    void warmsAllFourCaches() {
        listener.warmUp(ACCOUNT_ID, TENANT);

        verify(azureDashboardService).refreshAndCacheDashboard(SUB);
        verify(azureWasteService).getWasteItems(SUB, true);
        verify(azureRightsizingService).getRecommendations(SUB, true);
        verify(azureSecurityService).getSecurityData(SUB, true);
        verify(azureAlertsService).getAlerts(SUB, true);
    }

    @Test
    @DisplayName("a failing scan does not stop the remaining caches from warming")
    void oneFailureDoesNotStopTheOthers() {
        when(azureWasteService.getWasteItems(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("waste scan blew up"));

        listener.warmUp(ACCOUNT_ID, TENANT);

        // Waste threw, but rightsizing, security and alerts must still have been attempted.
        verify(azureRightsizingService).getRecommendations(SUB, true);
        verify(azureSecurityService).getSecurityData(SUB, true);
        verify(azureAlertsService).getAlerts(SUB, true);
    }
}
