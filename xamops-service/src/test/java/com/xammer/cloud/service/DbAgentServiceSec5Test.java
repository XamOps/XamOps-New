package com.xammer.cloud.service;

import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-5: {@link DbAgentService#saveConnectionConfig} no longer takes a password parameter and
 * must never write {@code db_agent_config.db_password}. This pins the going-forward guarantee
 * directly on the saved entity, beyond the structural no-parameter guarantee.
 */
class DbAgentServiceSec5Test {

    private static final String ACCOUNT = "111111111111";
    private static final String AGENT = "prod-db";

    private DbAgentConfigRepository dbAgentConfigRepository;
    private CloudAccountRepository cloudAccountRepository;
    private DbAgentService service;

    @BeforeEach
    void setUp() {
        dbAgentConfigRepository = mock(DbAgentConfigRepository.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);

        CloudAccount account = new CloudAccount();
        account.setId(1L);
        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(ACCOUNT, ACCOUNT, ACCOUNT))
                .thenReturn(Optional.of(account));
        when(dbAgentConfigRepository.findByCloudAccountIdAndAgentName(1L, AGENT)).thenReturn(Optional.empty());
        when(dbAgentConfigRepository.save(any(DbAgentConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new DbAgentService(dbAgentConfigRepository, mock(DbAgentDataCacheRepository.class),
                mock(DbAgentMetricsHistoryRepository.class), cloudAccountRepository,
                mock(ExplainRequestRepository.class), new DbOpsFeatureFlags(false));
    }

    @Test
    void saveConnectionConfig_neverWritesAPassword() {
        DbAgentConfig saved = service.saveConnectionConfig(ACCOUNT, AGENT,
                "postgres", "db.internal", "5432", "app", "xamops_reader", "https://server");

        assertThat(saved).isNotNull();
        assertThat(saved.getDbPassword()).isNull();

        // No entity that reaches the repository carries a password, on any save in the flow.
        ArgumentCaptor<DbAgentConfig> captor = ArgumentCaptor.forClass(DbAgentConfig.class);
        verify(dbAgentConfigRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(cfg -> assertThat(cfg.getDbPassword()).isNull());
    }
}
