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
import com.xammer.cloud.repository.VmssSpotConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import java.net.URL;
import java.util.List;
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

        String roleArn = String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName());

        // The EventBridge callback may have already connected this account — most likely when a
        // slow stack pushed the browser onto the manual form and then reported in behind it.
        // Re-submitting the same role is then a no-op, not an error: without this the customer
        // is told verification failed for an account that is connected and working.
        if ("CONNECTED".equals(account.getStatus()) && roleArn.equals(account.getRoleArn())) {
            logger.info("Account {} was already connected via callback; treating manual verification as a no-op.",
                    account.getAccountName());
            return account;
        }

        if (!"PENDING".equals(account.getStatus()) && !"FAILED".equals(account.getStatus())) {
            throw new RuntimeException("Account is not in a PENDING or FAILED state.");
        }

        String previousStatus = account.getStatus();
        account.setAwsAccountId(request.getAwsAccountId());
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
    @Autowired private DiskAlertStateRepository diskAlertStateRepository;
    @Autowired private SreInvestigationRepository sreInvestigationRepository;
    @Autowired private RedisCacheService redisCacheService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void deleteAccountById(Long accountId) {
        deleteFinancialDependents(accountId);

        resourceLabelAssignmentRepository.deleteByCloudAccountId(accountId);
        ebsVolumeActionRepository.deleteByAccountId(accountId);
        spotOptimizationEventRepository.deleteByAccountId(accountId);
        dailySpotSavingsRepository.deleteByAccountId(accountId);
        asgSpotConfigRepository.deleteByAccountId(accountId);
        statefulAsgConfigRepository.deleteByAccount_Id(accountId);
        vmssSpotConfigRepository.deleteByAccountId(accountId);
        diskPolicyRepository.deleteByAccountId(accountId);
        diskAlertStateRepository.deleteByAccount_Id(accountId);
        autoSpottingSettingsRepository.deleteByAccountId(accountId);
        finOpsReportScheduleRepository.deleteByCloudAccountId(accountId);
        dbAgentConfigRepository.deleteByCloudAccountId(accountId);
        deleteOrphanedAccountTables(accountId);
        deleteClusterAgentData(accountId);
        kubernetesClusterConfigRepository.deleteByCloudAccountId(accountId);
        kubernetesClusterRepository.deleteByCloudAccount_Id(accountId);
        userCloudAccountAccessRepository.deleteByCloudAccountId(accountId);
        sreInvestigationRepository.detachCloudAccount(accountId);
        cloudAccountRepository.deleteById(accountId);

        // Force the DELETE out now so any remaining foreign key surfaces inside this
        // call — at commit time it would escape the caller's try/catch and land in the
        // generic 500 handler as an opaque "constraint" message.
        //
        // flush() raises PersistenceException, which Spring only translates to
        // DataIntegrityViolationException across a repository proxy — not for a direct
        // EntityManager call in a service. Untranslated it bypasses the caller's
        // catch entirely, which is what turned a nameable foreign key into a bare 500.
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            throw new DataIntegrityViolationException(
                    "Could not delete cloud account " + accountId, e);
        }

        clearAllCaches();
    }

    /**
     * Clears the agent rows hanging off this account's cluster configs.
     *
     * <p>
     * {@code agent_data_cache} and {@code agent_events} key off
     * {@code kubernetes_cluster_config.id}, so they are grandchildren of the account
     * rather than dependents of it — nothing above touches them. V20260217 declares both
     * foreign keys {@code ON DELETE CASCADE}, but it creates the tables with
     * {@code CREATE TABLE IF NOT EXISTS} and ddl-auto had already made them, so the
     * statement was skipped and the cascade rule was never applied to
     * {@code agent_data_cache}. Deleting the rows here does not depend on which rule the
     * database actually ended up with.
     *
     * <p>
     * Runs before the cluster configs are deleted, while the subquery can still resolve
     * them.
     */
    private void deleteClusterAgentData(Long accountId) {
        if (!hasColumn("kubernetes_cluster_config", "cloud_account_id")) {
            return;
        }
        for (String table : List.of("agent_data_cache", "agent_events")) {
            if (!hasColumn(table, "cluster_config_id")) {
                continue;
            }
            entityManager.createNativeQuery("DELETE FROM " + table
                    + " WHERE cluster_config_id IN "
                    + "(SELECT id FROM kubernetes_cluster_config WHERE cloud_account_id = :id)")
                    .setParameter("id", accountId)
                    .executeUpdate();
        }
    }

    /**
     * Clears tables that still carry a non-cascading foreign key to {@code cloud_account}
     * but no longer have an entity or repository in this codebase — leftovers from code
     * that has since been removed. Nothing else deletes them, so a single surviving row
     * blocks the account delete with no Java-side owner to point at.
     */
    private void deleteOrphanedAccountTables(Long accountId) {
        for (String table : List.of("agent_commands", "rightsizing_history", "rightsizing_recommendations")) {
            if (!hasColumn(table, "account_id")) {
                continue;
            }
            entityManager.createNativeQuery("DELETE FROM " + table + " WHERE account_id = :id")
                    .setParameter("id", accountId)
                    .executeUpdate();
        }
    }

    /**
     * Destroys the billops-owned financial records belonging to this account so the
     * cloud_account row can be deleted.
     *
     * <p>
     * billops lives in a separate service against the same database and runs
     * ddl-auto=update, so its invoice and agreement tables carry NOT NULL foreign keys
     * to cloud_account with no ON DELETE rule — Postgres rejects the account DELETE
     * while any of these rows exist. Product decision is that removing an account
     * removes its billing history with it, so we clear them here.
     *
     * <p>
     * This is irreversible and applies to FINALIZED invoices too, which the billops
     * admin API refuses to delete on its own. Every removal is logged at WARN so the
     * destruction is at least reconstructible from the application log.
     */
    private void deleteFinancialDependents(Long accountId) {
        logOrphanedAgreementFiles(accountId);

        // Children first: invoice_line_item and discount both hold a NOT NULL
        // invoice_id, so the invoice rows cannot go until these do.
        int lineItems = deleteInvoiceChildren("invoice_line_item", accountId);
        int discounts = deleteInvoiceChildren("discount", accountId);
        int invoices = deleteByCloudAccount("invoice", accountId);
        int agreements = deleteByCloudAccount("agreement", accountId);

        if (invoices > 0 || agreements > 0) {
            logger.warn(
                    "Account {} deleted along with its financial records: {} invoice(s) "
                            + "({} line item(s), {} discount(s)) and {} agreement(s). This is not recoverable.",
                    accountId, invoices, lineItems, discounts, agreements);
            evictBillopsInvoiceCaches();
        }
    }

    /**
     * Clears the invoice caches billops keeps in the shared Redis.
     *
     * <p>
     * Deleting the rows with native SQL from this service never runs billops'
     * {@code @CacheEvict}, so its lists would keep serving invoices whose rows are gone.
     * The names below mirror the cache names and manual key billops uses; keep them in
     * sync with InvoiceManagementService if either changes.
     */
    private void evictBillopsInvoiceCaches() {
        for (String pattern : List.of("invoices::*", "invoiceDtos::*", "billops:invoices:list:admin*")) {
            try {
                redisCacheService.evictByPattern(pattern);
            } catch (Exception e) {
                // A stale cache entry is a display problem, not a reason to fail a
                // deletion whose rows are already gone.
                logger.warn("Could not evict billops invoice cache '{}': {}", pattern, e.getMessage());
            }
        }
    }

    /**
     * Records the S3 keys of the agreements about to be removed. The objects themselves
     * live in a bucket owned by billops, which this service has no client for, so they
     * are left in place — the log is what makes them findable for later cleanup.
     */
    private void logOrphanedAgreementFiles(Long accountId) {
        if (!linksToCloudAccount("agreement")) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> keys = entityManager
                .createNativeQuery("SELECT s3_key FROM agreement WHERE cloud_account_id = :id")
                .setParameter("id", accountId)
                .getResultList();
        if (!keys.isEmpty()) {
            logger.warn("Deleting account {} orphans {} agreement file(s) in S3, keys: {}",
                    accountId, keys.size(), keys);
        }
    }

    private int deleteByCloudAccount(String table, Long accountId) {
        if (!linksToCloudAccount(table)) {
            return 0;
        }
        return entityManager.createNativeQuery("DELETE FROM " + table + " WHERE cloud_account_id = :id")
                .setParameter("id", accountId)
                .executeUpdate();
    }

    private int deleteInvoiceChildren(String table, Long accountId) {
        if (!linksToCloudAccount("invoice") || !hasColumn(table, "invoice_id")) {
            return 0;
        }
        return entityManager.createNativeQuery("DELETE FROM " + table
                + " WHERE invoice_id IN (SELECT id FROM invoice WHERE cloud_account_id = :id)")
                .setParameter("id", accountId)
                .executeUpdate();
    }

    private boolean linksToCloudAccount(String table) {
        return hasColumn(table, "cloud_account_id");
    }

    /**
     * billops may never have run against this database, leaving its tables absent, so
     * every statement above is gated on the column actually existing. The probe is
     * deliberately an information_schema lookup rather than a try/catch — a failed
     * statement aborts the surrounding Postgres transaction, which would poison the
     * account deletes that follow even though Java caught the exception.
     */
    private boolean hasColumn(String table, String column) {
        Number found = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = :table AND column_name = :column "
                        + "AND table_schema = ANY (current_schemas(false))")
                .setParameter("table", table)
                .setParameter("column", column)
                .getSingleResult();
        return found.longValue() > 0;
    }
}