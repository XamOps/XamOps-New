package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.repository.SreInvestigationRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SreInvestigationServiceTest {

    private SreInvestigationRepository investigationRepo;
    private SreInvestigationService service;

    @BeforeEach
    void setUp() {
        investigationRepo = mock(SreInvestigationRepository.class);
        service = new SreInvestigationService(
            investigationRepo,
            mock(SreNotificationRepository.class),
            mock(OpenSreClient.class),
            mock(CloudAccountRepository.class),
            mock(ObservabilityConfigRepository.class),
            mock(SreNotificationService.class),
            mock(AwsClientProvider.class),
            "http://openobserve:5080"
        );
        // Self-injection field (used for @Async proxy) is null in unit tests.
        // Set it to a mock so createInvestigation() can call self.runInvestigationAsync()
        // without actually executing the async investigation flow.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "self", mock(SreInvestigationService.class));
    }

    @Test
    void createInvestigation_savesPendingJob() {
        SreInvestigation saved = new SreInvestigation();
        saved.setId(1L);
        saved.setStatus(SreInvestigation.InvestigationStatus.PENDING);
        when(investigationRepo.save(any())).thenReturn(saved);

        TriggerInvestigationRequest req = new TriggerInvestigationRequest();
        req.setResourceType(SreInvestigation.ResourceType.K8S_POD);
        req.setResourceId("payments-service-7d9f");
        req.setResourceName("payments-service");
        req.setCloudAccountId(10L);

        SreInvestigation result = service.createInvestigation(1L, req, SreInvestigation.TriggerType.MANUAL);

        assertThat(result.getStatus()).isEqualTo(SreInvestigation.InvestigationStatus.PENDING);
        verify(investigationRepo).save(any(SreInvestigation.class));
    }

    @Test
    void listForClient_delegatesToRepository() {
        when(investigationRepo.findByClientIdOrderByCreatedAtDesc(42L)).thenReturn(List.of());
        service.listForClient(42L);
        verify(investigationRepo).findByClientIdOrderByCreatedAtDesc(42L);
    }
}
