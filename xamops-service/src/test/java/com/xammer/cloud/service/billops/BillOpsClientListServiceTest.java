package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.dto.billops.BillOpsAccountDto;
import com.xammer.cloud.dto.billops.BillOpsClientDetailsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.repository.UpwindInventoryAssetRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.azure.AzureCostService;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The client list behind the BillOps admin Accounts page, and in particular its Security column.
 *
 * <p>The page shows "Security n/a" when {@code upwindEligibleAccounts} is 0, so that number has to
 * be right in the list the page is actually served: the one the background warmer caches, built
 * with {@link BillOpsClientListService#buildFromCacheOnly}. These tests pin the Upwind columns on
 * that build, that it stays off the cloud APIs, and that the request build still goes live.
 */
@ExtendWith(MockitoExtension.class)
class BillOpsClientListServiceTest {

    private static final long CLIENT_ID = 1L;
    private static final String AWS_ID = "111111111111";
    private static final String GCP_ID = "xammer-gcp";
    private static final String AZURE_ID = "904f8213-cabb-4591-b6e4-2cb80ac93158";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @Mock private CachedDataRepository cachedDataRepository;
    @Mock private CostService costService;
    @Mock private GcpCostService gcpCostService;
    @Mock private AzureCostService azureCostService;
    @Mock private UpwindAccountConnectionRepository upwindConnectionRepository;
    @Mock private UpwindInventoryAssetRepository upwindInventoryRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private BillOpsClientListService service;
    private Client client;

    @BeforeEach
    void setUp() {
        service = new BillOpsClientListService(cloudAccountRepository, userRepository,
                userCloudAccountAccessRepository, cachedDataRepository, objectMapper, costService,
                gcpCostService, azureCostService, upwindConnectionRepository, upwindInventoryRepository);
        client = new Client("Default Client");
        client.setId(CLIENT_ID);
    }

    @Test
    @DisplayName("An AWS account connected to Upwind counts as eligible and connected, with its workloads")
    void connectedAwsAccountIsReported() {
        givenAccounts(account(10L, "AWS", AWS_ID));
        when(upwindConnectionRepository.findByCloudAccountIdIn(List.of(AWS_ID)))
                .thenReturn(List.of(connection(AWS_ID, "CONNECTED")));
        List<Object[]> coverage = List.of(
                new Object[] {AWS_ID, "ec2", "sensor", 3L},
                new Object[] {AWS_ID, "ec2", null, 2L});
        when(upwindInventoryRepository.coverageBreakdown(List.of(AWS_ID), "compute_platform"))
                .thenReturn(coverage);

        BillOpsClientDetailsDto row = service.buildFromCacheOnly(List.of(client)).get(0);

        assertThat(row.getUpwindEligibleAccounts()).isEqualTo(1);
        assertThat(row.getUpwindConnectedAccounts()).isEqualTo(1);
        assertThat(row.getUpwindPendingAccounts()).isZero();
        assertThat(row.getUpwindWorkloads()).isEqualTo(5);
        assertThat(row.getUpwindSensorWorkloads()).isEqualTo(3);
        assertThat(row.getAccounts().get(0).getUpwindStatus()).isEqualTo("CONNECTED");
    }

    @Test
    @DisplayName("An AWS account Upwind has no record of is eligible but not connected, rather than n/a")
    void unconnectedAwsAccountIsEligible() {
        givenAccounts(account(10L, "AWS", AWS_ID));

        BillOpsClientDetailsDto row = service.buildFromCacheOnly(List.of(client)).get(0);

        assertThat(row.getUpwindEligibleAccounts()).isEqualTo(1);
        assertThat(row.getUpwindConnectedAccounts()).isZero();
        assertThat(row.getAccounts().get(0).getUpwindStatus()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    @DisplayName("A client with no AWS account has nothing eligible, which the page shows as n/a")
    void clientWithoutAwsAccountsHasNothingEligible() {
        givenAccounts(account(20L, "GCP", GCP_ID));

        BillOpsClientDetailsDto row = service.buildFromCacheOnly(List.of(client)).get(0);

        assertThat(row.getUpwindEligibleAccounts()).isZero();
        assertThat(row.getAccounts().get(0).getUpwindStatus()).isNull();
    }

    @Test
    @DisplayName("The Upwind fields survive the trip through the Redis cache")
    void upwindFieldsSurviveTheCache() throws Exception {
        givenAccounts(account(10L, "AWS", AWS_ID));
        when(upwindConnectionRepository.findByCloudAccountIdIn(List.of(AWS_ID)))
                .thenReturn(List.of(connection(AWS_ID, "CONNECTED")));

        String json = objectMapper.writeValueAsString(service.buildFromCacheOnly(List.of(client)));
        List<BillOpsClientDetailsDto> cached =
                objectMapper.readValue(json, new TypeReference<List<BillOpsClientDetailsDto>>() {});

        assertThat(cached.get(0).getUpwindEligibleAccounts()).isEqualTo(1);
        assertThat(cached.get(0).getUpwindConnectedAccounts()).isEqualTo(1);
        assertThat(cached.get(0).getAccounts().get(0).getUpwindStatus()).isEqualTo("CONNECTED");
    }

    @Test
    @DisplayName("Client settings come from the database, not from the DTO defaults")
    void clientSettingsAreCopied() {
        client.setXamSecureOnboardingEnabled(false);
        client.setSmsNotificationsEnabled(true);
        client.setWhatsappNotificationsEnabled(true);

        BillOpsClientDetailsDto row = service.buildFromCacheOnly(List.of(client)).get(0);

        assertThat(row.isXamSecureOnboardingEnabled()).isFalse();
        assertThat(row.isSmsNotificationsEnabled()).isTrue();
        assertThat(row.isWhatsappNotificationsEnabled()).isTrue();
    }

    @Test
    @DisplayName("The cache-only build reads spend from the DB cache and never calls AWS, GCP or Azure")
    void cacheOnlyBuildMakesNoCloudCalls() {
        givenAccounts(account(10L, "AWS", AWS_ID), account(20L, "GCP", GCP_ID), account(30L, "Azure", AZURE_ID));
        when(cachedDataRepository.findAllById(any()))
                .thenReturn(List.of(new CachedData("AWS_MTD_COST::" + AWS_ID, "42.0", null)));

        List<BillOpsAccountDto> accounts = service.buildFromCacheOnly(List.of(client)).get(0).getAccounts();

        assertThat(accounts.get(0).getConsumption()).isEqualTo(42.0);
        assertThat(accounts.get(0).getCreditsApplied()).isZero();
        assertThat(accounts.get(1).getConsumption()).isZero();
        assertThat(accounts.get(2).getConsumption()).isZero();
        verifyNoInteractions(costService, gcpCostService, azureCostService);
    }

    @Test
    @DisplayName("The request build still fetches uncached spend and AWS credits live")
    void requestBuildFetchesLive() {
        givenAccounts(account(10L, "AWS", AWS_ID));
        when(costService.getTotalMonthToDateCost(AWS_ID, false)).thenReturn(CompletableFuture.completedFuture(12.5));
        when(costService.getCreditsApplied(AWS_ID)).thenReturn(CompletableFuture.completedFuture(3.0));

        BillOpsClientDetailsDto row = service.build(List.of(client)).get(0);

        assertThat(row.getAccounts().get(0).getConsumption()).isEqualTo(12.5);
        assertThat(row.getAccounts().get(0).getCreditsApplied()).isEqualTo(3.0);
        assertThat(row.getTotalConsumption()).isEqualTo(12.5);
    }

    private void givenAccounts(CloudAccount... accounts) {
        when(cloudAccountRepository.findByClientIdIn(List.of(CLIENT_ID))).thenReturn(List.of(accounts));
    }

    private CloudAccount account(long id, String provider, String providerAccountId) {
        CloudAccount account = new CloudAccount();
        account.setId(id);
        account.setAccountName(provider + " account " + id);
        account.setProvider(provider);
        switch (provider) {
            case "AWS" -> account.setAwsAccountId(providerAccountId);
            case "GCP" -> account.setGcpProjectId(providerAccountId);
            default -> account.setAzureSubscriptionId(providerAccountId);
        }
        account.setClient(client);
        return account;
    }

    private static UpwindAccountConnection connection(String cloudAccountId, String status) {
        UpwindAccountConnection connection = new UpwindAccountConnection();
        connection.setCloudAccountId(cloudAccountId);
        connection.setStatus(status);
        return connection;
    }
}
