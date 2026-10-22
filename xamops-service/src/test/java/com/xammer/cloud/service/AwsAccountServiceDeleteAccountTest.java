package com.xammer.cloud.service;

import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DiskAlertStateRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.repository.SreInvestigationRepository;
import com.xammer.cloud.repository.StatefulAsgConfigRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Account deletion after Azure AutoSpotting was removed: its VMSS config repository is
 * gone, so {@code vmss_spot_config} is cleared through the hasColumn-guarded orphan-table
 * sweep instead. Deleting an Azure account must work whether or not the table still exists.
 */
class AwsAccountServiceDeleteAccountTest {

    private static final String VMSS_DELETE = "DELETE FROM vmss_spot_config WHERE account_id = :id";

    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final List<String> recordedSql = new ArrayList<>();
    private boolean vmssTableExists;

    private AwsAccountService service;

    @BeforeEach
    void setUp() {
        StsClient stsClient = mock(StsClient.class);
        // getCallerIdentity() returns null; the constructor catches the resulting NPE.
        service = new AwsAccountService(cloudAccountRepository, mock(ClientRepository.class),
                mock(AwsClientProvider.class), stsClient, mock(CacheService.class),
                mock(ApplicationEventPublisher.class),
                mock(com.xammer.cloud.service.cur.CurAutoRegistrationService.class));

        ReflectionTestUtils.setField(service, "resourceLabelAssignmentRepository",
                mock(ResourceLabelAssignmentRepository.class));
        ReflectionTestUtils.setField(service, "ebsVolumeActionRepository", mock(EbsVolumeActionRepository.class));
        ReflectionTestUtils.setField(service, "spotOptimizationEventRepository",
                mock(SpotOptimizationEventRepository.class));
        ReflectionTestUtils.setField(service, "dailySpotSavingsRepository", mock(DailySpotSavingsRepository.class));
        ReflectionTestUtils.setField(service, "asgSpotConfigRepository", mock(AsgSpotConfigRepository.class));
        ReflectionTestUtils.setField(service, "statefulAsgConfigRepository", mock(StatefulAsgConfigRepository.class));
        ReflectionTestUtils.setField(service, "diskPolicyRepository", mock(DiskPolicyRepository.class));
        ReflectionTestUtils.setField(service, "diskAlertStateRepository", mock(DiskAlertStateRepository.class));
        ReflectionTestUtils.setField(service, "autoSpottingSettingsRepository",
                mock(AutoSpottingSettingsRepository.class));
        ReflectionTestUtils.setField(service, "finOpsReportScheduleRepository",
                mock(FinOpsReportScheduleRepository.class));
        ReflectionTestUtils.setField(service, "dbAgentConfigRepository", mock(DbAgentConfigRepository.class));
        ReflectionTestUtils.setField(service, "kubernetesClusterConfigRepository",
                mock(KubernetesClusterConfigRepository.class));
        ReflectionTestUtils.setField(service, "kubernetesClusterRepository", mock(KubernetesClusterRepository.class));
        ReflectionTestUtils.setField(service, "userCloudAccountAccessRepository",
                mock(UserCloudAccountAccessRepository.class));
        ReflectionTestUtils.setField(service, "sreInvestigationRepository", mock(SreInvestigationRepository.class));
        ReflectionTestUtils.setField(service, "redisCacheService", mock(RedisCacheService.class));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            recordedSql.add(inv.getArgument(0));
            return fakeQuery();
        });
    }

    /**
     * A per-call Query whose information_schema probe reports a column only for
     * {@code vmss_spot_config}, and only while the test says that table exists.
     */
    private Query fakeQuery() {
        Query query = mock(Query.class);
        Map<String, Object> params = new HashMap<>();
        when(query.setParameter(anyString(), any())).thenAnswer(inv -> {
            params.put(inv.getArgument(0), inv.getArgument(1));
            return query;
        });
        when(query.getSingleResult()).thenAnswer(inv ->
                vmssTableExists && "vmss_spot_config".equals(params.get("table")) ? 1L : 0L);
        when(query.executeUpdate()).thenReturn(0);
        when(query.getResultList()).thenReturn(List.of());
        return query;
    }

    @Test
    void deleteAccountById_azureAccount_succeedsWhenVmssTableIsGone() {
        vmssTableExists = false;

        assertThatCode(() -> service.deleteAccountById(7L)).doesNotThrowAnyException();

        verify(cloudAccountRepository).deleteById(7L);
        assertThat(recordedSql).noneMatch(sql -> sql.startsWith("DELETE FROM vmss_spot_config"));
    }

    @Test
    void deleteAccountById_clearsLingeringVmssRows() {
        vmssTableExists = true;

        service.deleteAccountById(7L);

        assertThat(recordedSql).filteredOn(VMSS_DELETE::equals).hasSize(1);
        verify(cloudAccountRepository).deleteById(7L);
    }
}
