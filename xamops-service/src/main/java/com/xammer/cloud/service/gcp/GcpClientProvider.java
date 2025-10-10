package com.xammer.cloud.service.gcp;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.DnsScopes;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.appengine.v1.ServicesClient;
import com.google.appengine.v1.ServicesSettings;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.apigateway.v1.ApiGatewayServiceClient;
import com.google.cloud.apigateway.v1.ApiGatewayServiceSettings;
import com.google.cloud.asset.v1.AssetServiceClient;
import com.google.cloud.asset.v1.AssetServiceSettings;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.billing.budgets.v1.BudgetServiceClient;
import com.google.cloud.billing.budgets.v1.BudgetServiceSettings;
import com.google.cloud.billing.v1.CloudBillingClient;
import com.google.cloud.billing.v1.CloudBillingSettings;
import com.google.cloud.billing.v1.ProjectBillingInfo;
import com.google.cloud.billing.v1.ProjectName;
import com.google.cloud.compute.v1.*;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.container.v1.ClusterManagerSettings;
import com.google.cloud.devtools.cloudbuild.v1.CloudBuildClient;
import com.google.cloud.devtools.cloudbuild.v1.CloudBuildSettings;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.FunctionServiceSettings;
import com.google.cloud.iam.admin.v1.IAMClient;
import com.google.cloud.iam.admin.v1.IAMSettings;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.logging.v2.ConfigSettings;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderSettings;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.google.cloud.securitycenter.v2.SecurityCenterSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

@Service
@Slf4j
public class GcpClientProvider {

    private final CloudAccountRepository cloudAccountRepository;

    public GcpClientProvider(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public Optional<AssetServiceClient> getAssetServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                AssetServiceSettings settings = AssetServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return AssetServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create AssetServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    @Cacheable(value = "gcpCredentials", key = "#gcpProjectId")
    private Optional<GoogleCredentials> getCredentials(String gcpProjectId) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(gcpProjectId);
        if (accountOpt.isEmpty()) {
            log.error("GCP account not found for project ID: {}", gcpProjectId);
            return Optional.empty();
        }
        try {
            CloudAccount account = accountOpt.get();
            if (account.getGcpServiceAccountKey() == null || account.getGcpServiceAccountKey().isBlank()) {
                log.error("Credentials for GCP project ID: {} are missing.", gcpProjectId);
                return Optional.empty();
            }
            return Optional.of(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(account.getGcpServiceAccountKey().getBytes()))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform"));
        } catch (IOException e) {
            log.error("Failed to create GoogleCredentials for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    /**
     * Get credentials for billing account access
     * Uses the same service account key but can be used for billing account scope operations
     */
    @Cacheable(value = "gcpCredentialsByBillingAccount", key = "#billingAccountId")
    private Optional<GoogleCredentials> getCredentialsByBillingAccount(String billingAccountId) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpBillingAccountId(billingAccountId);
        if (accountOpt.isEmpty()) {
            log.error("GCP account not found for billing account ID: {}", billingAccountId);
            return Optional.empty();
        }
        try {
            CloudAccount account = accountOpt.get();
            if (account.getGcpServiceAccountKey() == null || account.getGcpServiceAccountKey().isBlank()) {
                log.error("Credentials for billing account ID: {} are missing.", billingAccountId);
                return Optional.empty();
            }
            return Optional.of(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(account.getGcpServiceAccountKey().getBytes()))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform"));
        } catch (IOException e) {
            log.error("Failed to create GoogleCredentials for billing account ID: {}", billingAccountId, e);
            return Optional.empty();
        }
    }

    public Optional<CloudBuildClient> getCloudBuildClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                CloudBuildSettings settings = CloudBuildSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return CloudBuildClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create CloudBuildClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<FunctionServiceClient> getFunctionServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                FunctionServiceSettings settings = FunctionServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return FunctionServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create FunctionServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<IAMClient> getIamClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                IAMSettings settings = IAMSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return IAMClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create IAMClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<KeyManagementServiceClient> getKeyManagementServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                KeyManagementServiceSettings settings = KeyManagementServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return KeyManagementServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create KeyManagementServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<RoutersClient> getRoutersClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                RoutersSettings settings = RoutersSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return RoutersClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RoutersClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<RoutesClient> getRoutesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                RoutesSettings settings = RoutesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return RoutesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RoutesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SecretManagerServiceClient> getSecretManagerServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SecretManagerServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SecretManagerServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SecurityPoliciesClient> getSecurityPoliciesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SecurityPoliciesSettings settings = SecurityPoliciesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SecurityPoliciesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SecurityPoliciesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    /**
     * Get RecommenderClient for project scope
     */
    public Optional<RecommenderClient> getRecommenderClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                RecommenderSettings settings = RecommenderSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return RecommenderClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RecommenderClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    /**
     * Get RecommenderClient for billing account scope
     * Required for fetching CUD recommendations at billing account level
     *
     * The service account must have the 'Billing Account Viewer' role
     * or 'recommender.billingAccountCudRecommendations.list' permission
     */
    public Optional<RecommenderClient> getRecommenderClientForBillingAccount(String billingAccountId) {
        return getCredentialsByBillingAccount(billingAccountId).map(credentials -> {
            try {
                RecommenderSettings settings = RecommenderSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                log.info("Created RecommenderClient for billing account: {}", billingAccountId);
                return RecommenderClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RecommenderClient for billing account ID: {}", billingAccountId, e);
                return null;
            }
        });
    }

    public Optional<SecurityCenterClient> getSecurityCenterV2Client(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SecurityCenterSettings settings = SecurityCenterSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SecurityCenterClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SecurityCenterClient V2 for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<InstancesClient> getInstancesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                InstancesSettings settings = InstancesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return InstancesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create InstancesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<Storage> getStorageClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials ->
                StorageOptions.newBuilder()
                        .setCredentials(credentials)
                        .setProjectId(gcpProjectId)
                        .build()
                        .getService());
    }

    public Optional<BigQuery> getBigQueryClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials ->
                BigQueryOptions.newBuilder()
                        .setCredentials(credentials)
                        .setProjectId(gcpProjectId)
                        .build()
                        .getService());
    }

    public Optional<MetricServiceClient> getMetricServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                MetricServiceSettings settings = MetricServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return MetricServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create MetricServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<NetworksClient> getNetworksClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                NetworksSettings settings = NetworksSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return NetworksClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create NetworksClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SubnetworksClient> getSubnetworksClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SubnetworksSettings settings = SubnetworksSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SubnetworksClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SubnetworksClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ClusterManagerClient> getClusterManagerClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ClusterManagerSettings settings = ClusterManagerSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ClusterManagerClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ClusterManagerClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ProjectsClient> getProjectsClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ProjectsSettings settings = ProjectsSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ProjectsClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ProjectsClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SQLAdmin> getSqlAdminClient(String gcpProjectId) {
        try {
            Optional<GoogleCredentials> credsOpt = getCredentials(gcpProjectId);
            if (credsOpt.isEmpty()) {
                return Optional.empty();
            }
            GoogleCredentials credentials = credsOpt.get();
            var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            var jsonFactory = new GsonFactory();

            SQLAdmin sqlAdmin = new SQLAdmin.Builder(
                    httpTransport,
                    jsonFactory,
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("xamops-app")
                    .build();
            return Optional.of(sqlAdmin);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to create SQLAdmin client for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    /**
     * Get Cloud Billing client (modern library)
     * Uses google-cloud-billing instead of google-api-services-cloudbilling
     */
    public Optional<CloudBillingClient> getCloudBillingClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                CloudBillingSettings settings = CloudBillingSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return CloudBillingClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create CloudBillingClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    /**
     * Fetch billing account ID for a GCP project
     * This method automatically retrieves the billing account ID from GCP Billing API
     */
    public Optional<String> getBillingAccountIdForProject(String gcpProjectId) {
        try {
            Optional<CloudBillingClient> billingClientOpt = getCloudBillingClient(gcpProjectId);
            if (billingClientOpt.isEmpty()) {
                log.error("CloudBillingClient not available for project {}", gcpProjectId);
                return Optional.empty();
            }

            try (CloudBillingClient billingClient = billingClientOpt.get()) {
                ProjectName projectName = ProjectName.of(gcpProjectId);

                ProjectBillingInfo billingInfo = billingClient.getProjectBillingInfo(projectName);

                if (billingInfo.getBillingEnabled() && !billingInfo.getBillingAccountName().isEmpty()) {
                    // billingAccountName format: "billingAccounts/01B1D1-936E5A-E97A8C"
                    String billingAccountName = billingInfo.getBillingAccountName();
                    String billingAccountId = billingAccountName.replace("billingAccounts/", "");

                    log.info("Retrieved billing account ID {} for project {}", billingAccountId, gcpProjectId);
                    return Optional.of(billingAccountId);
                } else {
                    log.warn("Billing not enabled or no billing account for project {}", gcpProjectId);
                    return Optional.empty();
                }
            }
        } catch (Exception e) {
            log.error("Failed to get billing account ID for project {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    public Optional<Storage> createStorageClient(String serviceAccountKey) {
        try {
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountKey.getBytes()))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            return Optional.of(StorageOptions.newBuilder().setCredentials(credentials).build().getService());
        } catch (IOException e) {
            log.error("Failed to create Storage client", e);
            return Optional.empty();
        }
    }

    public Optional<Dns> getDnsZonesClient(String gcpProjectId) {
        try {
            Optional<GoogleCredentials> credsOpt = getCredentials(gcpProjectId);
            if (credsOpt.isEmpty()) return Optional.empty();
            GoogleCredentials credentials = credsOpt.get().createScoped(DnsScopes.all());
            var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            var jsonFactory = new GsonFactory();
            Dns dns = new Dns.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(credentials))
                    .setApplicationName("xamops-app")
                    .build();
            return Optional.of(dns);
        } catch (Exception e) {
            log.error("Failed to create Dns client for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    public Optional<BudgetServiceClient> getBudgetServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                BudgetServiceSettings settings = BudgetServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return BudgetServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create BudgetServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ForwardingRulesClient> getForwardingRulesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ForwardingRulesSettings settings = ForwardingRulesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ForwardingRulesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ForwardingRulesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<FirewallsClient> getFirewallsClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                FirewallsSettings settings = FirewallsSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return FirewallsClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create FirewallsClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<DisksClient> getDisksClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                DisksSettings settings = DisksSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return DisksClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create DisksClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ImagesClient> getImagesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ImagesSettings settings = ImagesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ImagesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ImagesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ApiGatewayServiceClient> getApiGatewayServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ApiGatewayServiceSettings settings = ApiGatewayServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ApiGatewayServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ApiGatewayServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ServicesClient> getAppEngineServicesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ServicesSettings settings = ServicesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ServicesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ServicesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ConfigClient> getConfigClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ConfigSettings settings = ConfigSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ConfigClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ConfigClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<RegionCommitmentsClient> getRegionCommitmentsClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                RegionCommitmentsSettings settings = RegionCommitmentsSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return RegionCommitmentsClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RegionCommitmentsClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }
}
