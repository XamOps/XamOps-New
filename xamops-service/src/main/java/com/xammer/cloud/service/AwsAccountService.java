package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.repository.StatefulAsgConfigRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.VmssSpotConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URL;
import java.util.Map;
import java.util.UUID;

@Service
public class AwsAccountService {

    private static final Logger logger = LoggerFactory.getLogger(AwsAccountService.class);

    private final String hostAccountId;
    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    public final AwsClientProvider awsClientProvider;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${cloudformation.template.s3.url}")
    private String cloudFormationTemplateUrl;

    @Value("${cloudformation.template.s3.readwrite.url}")
    private String cloudFormationReadWriteTemplateUrl;

    @Value("${xamops.cfn.callback.param.name:}")
    private String cfnCallbackParamName;

    @Value("${xamops.cfn.callback.param.value:}")
    private String cfnCallbackParamValue;

    @Autowired
    public AwsAccountService(
            CloudAccountRepository cloudAccountRepository,
            ClientRepository clientRepository,
            AwsClientProvider awsClientProvider,
            StsClient stsClient,
            CacheService cacheService,
            ApplicationEventPublisher eventPublisher) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;

        String tmpAccountId;
        try {
            tmpAccountId = stsClient.getCallerIdentity().account();
        } catch (Exception e) {
            logger.error("Could not determine host AWS Account ID on startup.", e);
            tmpAccountId = "HOST_ACCOUNT_ID_NOT_FOUND";
        }
        this.hostAccountId = tmpAccountId;
    }

    public Map<String, Object> generateCloudFormationUrl(String accountName, String awsAccountId, String accessType,
            Long clientId) throws Exception {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));

        String externalId = UUID.randomUUID().toString();

        CloudAccount newAccount = new CloudAccount(accountName, externalId, accessType, client);

        // Fix: If awsAccountId is empty (common for pending accounts), set it to null
        // to avoid unique constraint violations (multiple nulls allowed, multiple ""
        // not allowed)
        if (awsAccountId != null && awsAccountId.trim().isEmpty()) {
            awsAccountId = null;
        }

        newAccount.setAwsAccountId(awsAccountId);
        newAccount.setProvider("AWS");
        cloudAccountRepository.save(newAccount);

        String stackName = "XamOps-Connection-" + accountName.replaceAll("[^a-zA-Z0-9-]", "");
        String xamopsAccountId = this.hostAccountId;
        String templateUrl = "read-write".equals(accessType) ? cloudFormationReadWriteTemplateUrl : cloudFormationTemplateUrl;

        boolean eventBridgeEnabled = cfnCallbackParamName != null && !cfnCallbackParamName.isEmpty();
        String callbackParam = eventBridgeEnabled
                ? "&param_" + cfnCallbackParamName + "=" + cfnCallbackParamValue
                : "";

        String urlString = String.format(
                "https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_XamOpsAccountId=%s&param_ExternalId=%s%s",
                templateUrl, stackName, xamopsAccountId, externalId, callbackParam);

        return Map.of(
                "url", new URL(urlString),
                "externalId", externalId,
                "eventBridgeEnabled", eventBridgeEnabled);
    }

    public CloudAccount verifyAccount(VerifyAccountRequest request) {
        CloudAccount account = cloudAccountRepository.findByExternalId(request.getExternalId())
                .orElseThrow(() -> new RuntimeException("No pending account found for the given external ID."));

        if (!"PENDING".equals(account.getStatus()) && !"FAILED".equals(account.getStatus())) {
            throw new RuntimeException("Account is not in a PENDING or FAILED state.");
        }

        String previousStatus = account.getStatus();
        account.setAwsAccountId(request.getAwsAccountId());
        String roleArn = String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName());
        account.setRoleArn(roleArn);

        // Evict any cached clients for this account so the new roleArn is used
        awsClientProvider.evictAccount(request.getAwsAccountId());

        try {
            Ec2Client testClient = awsClientProvider.getEc2Client(account, configuredRegion);
            testClient.describeRegions();

            boolean reconnected = "FAILED".equals(previousStatus);
            account.setStatus("CONNECTED");
            logger.info("Successfully verified and connected to account: {}", account.getAccountName());
            CloudAccount saved = cloudAccountRepository.save(account);
            publishConnected(saved, reconnected);
            return saved;
        } catch (Exception e) {
            account.setStatus("FAILED");
            logger.error("Failed to verify account {}: {}", account.getAccountName(), e.getMessage());
            cloudAccountRepository.save(account);
            awsClientProvider.evictAccount(request.getAwsAccountId());
            throw new RuntimeException(
                    "Role assumption failed. Please check the role ARN, trust policy, and external ID.", e);
        }
    }

    public CloudAccount connectViaCallback(String externalId, String awsAccountId, String roleArn) {
        CloudAccount account = cloudAccountRepository.findByExternalId(externalId)
                .orElseThrow(() -> new RuntimeException("No account found for externalId: " + externalId));

        if (!"PENDING".equals(account.getStatus()) && !"FAILED".equals(account.getStatus())) {
            logger.info("EventBridge callback: account {} already in status {}. Skipping.", externalId,
                    account.getStatus());
            return account;
        }

        String previousStatus = account.getStatus();
        account.setAwsAccountId(awsAccountId);
        account.setRoleArn(roleArn);
        awsClientProvider.evictAccount(awsAccountId);

        try {
            Ec2Client testClient = awsClientProvider.getEc2Client(account, configuredRegion);
            testClient.describeRegions();
            boolean reconnected = "FAILED".equals(previousStatus);
            account.setStatus("CONNECTED");
            logger.info("EventBridge callback: successfully connected account externalId={}", externalId);
            CloudAccount saved = cloudAccountRepository.save(account);
            publishConnected(saved, reconnected);
            return saved;
        } catch (Exception e) {
            account.setStatus("FAILED");
            logger.error("EventBridge callback: role assumption failed for externalId={}: {}", externalId,
                    e.getMessage());
            cloudAccountRepository.save(account);
            awsClientProvider.evictAccount(awsAccountId);
            throw new RuntimeException("Role assumption failed during EventBridge callback.", e);
        }
    }

    /**
     * Announces that an account reached CONNECTED, so listeners can react.
     *
     * <p>
     * Currently drives the onboarding WAR report email. Wrapped in a try/catch because
     * publishing must never turn a successful connection into a failed API response —
     * the account is connected regardless of who is listening.
     */
    private void publishConnected(CloudAccount account, boolean reconnected) {
        try {
            eventPublisher.publishEvent(new CloudAccountConnectedEvent(
                    account.getId(), account.getProvider(), reconnected));
        } catch (Exception e) {
            logger.warn("Could not publish connected event for account {}: {}",
                    account.getId(), e.getMessage());
        }
    }

    public void clearAllCaches() {
        cacheService.evictAllCaches();
    }

    @Autowired private ResourceLabelAssignmentRepository resourceLabelAssignmentRepository;
    @Autowired private EbsVolumeActionRepository ebsVolumeActionRepository;
    @Autowired private SpotOptimizationEventRepository spotOptimizationEventRepository;
    @Autowired private DailySpotSavingsRepository dailySpotSavingsRepository;
    @Autowired private AsgSpotConfigRepository asgSpotConfigRepository;
    @Autowired private StatefulAsgConfigRepository statefulAsgConfigRepository;
    @Autowired private VmssSpotConfigRepository vmssSpotConfigRepository;
    @Autowired private DiskPolicyRepository diskPolicyRepository;
    @Autowired private AutoSpottingSettingsRepository autoSpottingSettingsRepository;
    @Autowired private FinOpsReportScheduleRepository finOpsReportScheduleRepository;
    @Autowired private DbAgentConfigRepository dbAgentConfigRepository;
    @Autowired private KubernetesClusterConfigRepository kubernetesClusterConfigRepository;
    @Autowired private KubernetesClusterRepository kubernetesClusterRepository;
    @Autowired private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Transactional
    public void deleteAccountById(Long accountId) {
        resourceLabelAssignmentRepository.deleteByCloudAccountId(accountId);
        ebsVolumeActionRepository.deleteByAccountId(accountId);
        spotOptimizationEventRepository.deleteByAccountId(accountId);
        dailySpotSavingsRepository.deleteByAccountId(accountId);
        asgSpotConfigRepository.deleteByAccountId(accountId);
        statefulAsgConfigRepository.deleteByAccount_Id(accountId);
        vmssSpotConfigRepository.deleteByAccountId(accountId);
        diskPolicyRepository.deleteByAccountId(accountId);
        autoSpottingSettingsRepository.deleteByAccountId(accountId);
        finOpsReportScheduleRepository.deleteByCloudAccountId(accountId);
        dbAgentConfigRepository.deleteByCloudAccountId(accountId);
        kubernetesClusterConfigRepository.deleteByCloudAccountId(accountId);
        kubernetesClusterRepository.deleteByCloudAccount_Id(accountId);
        userCloudAccountAccessRepository.deleteByCloudAccountId(accountId);
        cloudAccountRepository.deleteById(accountId);
        clearAllCaches();
    }
}