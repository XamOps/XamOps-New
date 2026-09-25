package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.DescribeAddressesResponse;
import software.amazon.awssdk.services.ec2.model.ServiceManaged;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaResponse;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The network quota check against one region, with the xammer-local account's real ap-south-1
 * numbers: 13 Elastic IPs of which 8 are ALB-managed, and a limit of 5.
 */
class CloudGuardServiceQuotaTest {

    private static final String ACCOUNT_ID = "605134457560";
    private static final String REGION = "ap-south-1";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AwsClientProvider awsClientProvider;
    @Mock private RedisCacheService redisCache;
    @Mock private CloudListService cloudListService;
    @Mock private ServiceQuotasClient serviceQuotas;
    @Mock private Ec2Client ec2;

    private CloudGuardService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CloudGuardService(
                cloudAccountRepository, awsClientProvider, redisCache, cloudListService,
                mock(FinOpsService.class), mock(EmailService.class), mock(DashboardDataService.class),
                mock(AwsSecurityHubService.class), new AlertEmailBuilder(),
                mock(NotificationRecipientResolver.class), mock(AlertNotificationDeduplicator.class),
                mock(AlertDigestSeverityGate.class));

        CloudAccount account = mock(CloudAccount.class);
        when(account.getAwsAccountId()).thenReturn(ACCOUNT_ID);
        when(cloudAccountRepository.findByProviderAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account));

        DashboardData.RegionStatus region = new DashboardData.RegionStatus();
        region.setRegionId(REGION);
        when(cloudListService.getRegionStatusForAccountOrFail(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.of(region)));

        when(awsClientProvider.getServiceQuotasClient(any(), eq(REGION))).thenReturn(serviceQuotas);
        when(awsClientProvider.getEc2Client(any(), eq(REGION))).thenReturn(ec2);

        // No "vpc" quotas returned, so this test is about Elastic IPs alone.
        when(serviceQuotas.listServiceQuotas(any(ListServiceQuotasRequest.class)))
                .thenReturn(ListServiceQuotasResponse.builder().quotas(List.of()).build());
        when(serviceQuotas.getServiceQuota(any(GetServiceQuotaRequest.class)))
                .thenReturn(GetServiceQuotaResponse.builder().quota(ServiceQuota.builder()
                        .quotaCode("L-0263D0A3").quotaName("EC2-VPC Elastic IPs").value(5.0).build()).build());

        List<Address> addresses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            addresses.add(Address.builder().serviceManaged(ServiceManaged.ALB).build());
        }
        for (int i = 0; i < 5; i++) {
            addresses.add(Address.builder().build());
        }
        when(ec2.describeAddresses()).thenReturn(DescribeAddressesResponse.builder().addresses(addresses).build());
    }

    @Test
    void elasticIps_readAsFiveOfFive() {
        List<DashboardData.ServiceQuotaInfo> alerts = service.getNetworkQuotaAlerts(ACCOUNT_ID, true).join();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getServiceName()).isEqualTo("Elastic IP");
        assertThat(alerts.get(0).getUsage()).isEqualTo(5.0);
        assertThat(alerts.get(0).getLimit()).isEqualTo(5.0);
    }

    @Test
    void theElasticIpLimit_isFetchedDirectlyNotByListingAllOfEc2() {
        // Listing EC2's quotas took several calls a region and pushed the scan past the timeout.
        service.getNetworkQuotaAlerts(ACCOUNT_ID, true).join();

        verify(serviceQuotas).getServiceQuota(any(GetServiceQuotaRequest.class));
        verify(serviceQuotas, never()).listServiceQuotas(
                argThat((ListServiceQuotasRequest r) -> "ec2".equals(r.serviceCode())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedLimits_meanNoServiceQuotasCalls() {
        when(redisCache.get(argThat((String k) -> k != null && k.startsWith("quotaLimits-")), any(TypeReference.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return key.contains("-ec2-")
                            ? Optional.of(Map.of("L-0263D0A3", new CloudGuardService.QuotaLimit("EC2-VPC Elastic IPs", 5.0)))
                            : Optional.of(Map.of());
                });

        List<DashboardData.ServiceQuotaInfo> alerts = service.getNetworkQuotaAlerts(ACCOUNT_ID, false).join();

        assertThat(alerts).hasSize(1);
        verifyNoInteractions(serviceQuotas);
        // Usage is still counted live.
        verify(ec2).describeAddresses();
    }
}
