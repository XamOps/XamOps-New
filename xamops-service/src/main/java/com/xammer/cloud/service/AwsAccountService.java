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
import com.xammer.cloud.service.cur.CurAutoRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AwsAccountService {

    private static final Logger logger = LoggerFactory.getLogger(AwsAccountService.class);

    /**
     * The only region AWS::CUR::ReportDefinition exists in, and therefore where the
     * connection stack has to be created for its nested CUR report to work.
     */
    private static final String CUR_REPORT_REGION = "us-east-1";

    /**
     * The queue every role template names by default. An environment configured with this
     * is the one that owns it, and has nothing to override.
     */
    private static final String SHARED_QUEUE_NAME = "xamops-inventory-events.fifo";

    private final String hostAccountId;
    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    public final AwsClientProvider awsClientProvider;
    private final CacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;
    // Lazy to break the construction-time cycle: CurAutoRegistrationService's validation path
    // pulls in services that ultimately reference this one. We only need it at callback time.
    private final CurAutoRegistrationService curAutoRegistrationService;

    @Value("${cloudformation.template.s3.url}")
    private String cloudFormationTemplateUrl;

    @Value("${cloudformation.template.s3.readwrite.url}")
    private String cloudFormationReadWriteTemplateUrl;

    // The name the stack gives the role it creates. Each environment's template suffixes it
    // differently (-local, -uat) so a customer can hold connections to several environments in
    // one AWS account without the role names colliding. The UI prefills this for manual
    // verification, so it has to follow whichever template we actually hand out — defaults here
    // match the production templates.
    @Value("${xamops.role.readonly.name:XamOpsReadOnlyRole}")
    private String readOnlyRoleName;

    @Value("${xamops.role.readwrite.name:XamOpsReadWriteRole}")
    private String readWriteRoleName;

    /**
     * The queue this environment actually listens on.
     *
     * <p>Passed to the stack rather than left to the template's default, which names the
     * shared production queue. An environment with its own queue — local and UAT both have
     * one — would otherwise onboard accounts that forward to production, where the events
     * are discarded as belonging to an unknown account. Nothing fails; the environment that
     * onboarded the customer simply never sees their resources appear in under a minute,
     * and the events pile up in a queue nobody reads.
     *
     * <p>This was a documented manual step ("re-onboard the test account with
     * XamOpsInventoryQueueUrl pointing here"), which is the kind nobody remembers.
     */
    @Value("${xamops.inventory.events.queue-url:}")
    private String inventoryQueueUrl;

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
            ApplicationEventPublisher eventPublisher,
            @Lazy CurAutoRegistrationService curAutoRegistrationService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
        this.curAutoRegistrationService = curAutoRegistrationService;

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
        return generateCloudFormationUrl(accountName, awsAccountId, accessType, clientId, null, null);
    }

    /**
     * @param curS3Bucket bucket holding this account's Cost and Usage Report, or null/blank
     *                    when it has none. Blank makes the template's HasCurBucket condition
     *                    false, so no CUR permissions are attached at all — which is what an
     *                    account under a XamOps payer account, or one with no report, needs.
     */
    public Map<String, Object> generateCloudFormationUrl(String accountName, String awsAccountId, String accessType,
            Long clientId, String curS3Bucket, String awsBillingSource) throws Exception {
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
        boolean readWrite = "read-write".equals(accessType);
        String templateUrl = readWrite ? cloudFormationReadWriteTemplateUrl : cloudFormationTemplateUrl;
        String roleName = readWrite ? readWriteRoleName : readOnlyRoleName;

        boolean eventBridgeEnabled = cfnCallbackParamName != null && !cfnCallbackParamName.isEmpty();
        String callbackParam = eventBridgeEnabled
                ? "&param_" + cfnCallbackParamName + "=" + cfnCallbackParamValue
                : "";

        // The bucket may not exist yet: for an account that still has to deploy the CUR
        // stack, this names the bucket that stack will create. An IAM policy can reference
        // a bucket before it exists — it simply grants nothing until it does — which is what
        // lets the two stacks be launched in either order.
        String curBucketParam = (curS3Bucket != null && !curS3Bucket.isBlank())
                ? "&param_CurS3Bucket=" + URLEncoder.encode(curS3Bucket.trim(), StandardCharsets.UTF_8)
                : "";

        /*
         * Whether the connection stack creates the report, decided by the option the
         * operator picked rather than left at the template's default.
         *
         * Only "create a report in this account" should. An account under a XamOps payer
         * is already inside that payer's report; an account that already has one would be
         * paying to store a second copy; and an account whose payer never enabled billing
         * access cannot create one at all — for that last case the stack would fail rather
         * than quietly skip, which is the worst of the four outcomes.
         */
        boolean createReport = "OWN_CUR".equals(awsBillingSource);
        String curReportParam = "&param_EnableCurReport=" + (createReport ? "true" : "false");

        // Region comes from the queue URL rather than a second setting, so the two cannot
        // disagree: sqs.<region>.amazonaws.com is the only shape SQS publishes.
        String inventoryParams = "";
        if (inventoryQueueUrl != null && !inventoryQueueUrl.isBlank()) {
            inventoryParams = "&param_XamOpsInventoryQueueUrl="
                    + URLEncoder.encode(inventoryQueueUrl.trim(), StandardCharsets.UTF_8);
            String region = regionFromQueueUrl(inventoryQueueUrl.trim());
            if (region != null) {
                inventoryParams += "&param_XamOpsInventoryRegion="
                        + URLEncoder.encode(region, StandardCharsets.UTF_8);
            }
        }

        // Pinned to us-east-1 rather than left to whichever region the customer's console
        // happens to be showing.
        //
        // The connection stack now nests the CUR 1.0 report, and
        // AWS::CUR::ReportDefinition exists in us-east-1 alone — a nested stack is created
        // in its parent's region, so a parent anywhere else silently produces an account
        // with no report. Nothing else in this stack is region-bound: the IAM role is
        // global and the inventory nested stack fans out to every region by itself.
        //
        // The template also guards this with its own IsCurReportRegion condition, so a
        // customer who overrides the region still connects successfully — they just get no
        // report, which the stack diagnosis then reports as missing.
        String urlString = String.format(
                "https://console.aws.amazon.com/cloudformation/home?region=%s#/stacks/create/review"
                        + "?templateURL=%s&stackName=%s&param_XamOpsAccountId=%s&param_ExternalId=%s%s%s",
                CUR_REPORT_REGION, templateUrl, stackName, xamopsAccountId, externalId,
                callbackParam, curBucketParam + curReportParam + inventoryParams);

        return Map.of(
                "url", new URL(urlString),
                "externalId", externalId,
                "eventBridgeEnabled", eventBridgeEnabled,
                "roleName", roleName);
    }

    /**
     * What the customer has to do to bring an already-connected account onto the current
     * template — the stack they already have, updated in place.
     *
     * <p>Returns instructions rather than a one-click link because AWS has none to give:
     * quick-create links create stacks and cannot update them. The alternative, a second
     * stack bolted alongside the first, only ever patches what it was told to patch — it
     * cannot bring the rest of an old role up to date, and it leaves two definitions of
     * the same connection to drift apart. Four clicks against the real stack beats one
     * click against a copy of it.
     *
     * <p>Nothing here is written and no ExternalId is re-issued. CloudFormation's update
     * wizard carries existing parameter values forward, so the ExternalId in the role's
     * trust policy survives untouched as long as nobody edits it — which is why the
     * returned parameters list names only the two that must change.
     */
    public Map<String, Object> generateStackUpdateGuide(CloudAccount account) {
        boolean readWrite = "read-write".equals(account.getAccessType());
        String templateUrl = readWrite ? cloudFormationReadWriteTemplateUrl : cloudFormationTemplateUrl;
        if (templateUrl == null || templateUrl.isBlank()) {
            throw new IllegalStateException("No connection template is configured for this environment.");
        }

        // This environment's role name, the same source the connection stack uses:
        // XamOpsReadOnlyRole-local, -uat, and unsuffixed for preprod and production. Each
        // environment owns its own role in the customer's account, so one account can hold
        // connections to several without the names colliding.
        String roleName = readWrite ? readWriteRoleName : readOnlyRoleName;

        // The name the connection stack was created with. A best guess rather than a fact:
        // it is derived the same way generateCloudFormationUrl derives it, but an account
        // onboarded years ago may have been renamed since, so the console link filters
        // rather than deep-links to one stack.
        String likelyStackName = "XamOps-Connection-"
                + String.valueOf(account.getAccountName()).replaceAll("[^a-zA-Z0-9-]", "");

        /*
         * The parameters worth naming, and why.
         *
         * CurS3Bucket used to be the one that silently broke things — left at its default
         * the template attached no report permissions while still creating the report, so
         * the account ended up with a CUR that XamOps could not read and nothing failed to
         * say so. The template's HasCurBucket condition now derives that from whether a
         * report is being created, so the default is finally correct. It stays listed
         * because plenty of stacks were updated while the old advice stood and carry AUTO
         * already, and seeing it named avoids someone "tidying" it back to blank.
         *
         * EnableCurExport is CUR 2.0, now off by default: it and CUR 1.0 write the same
         * bucket name and only one can own it.
         */
        // Built rather than a constant list, because the inventory queue differs per
        // environment and the whole point is that nobody has to remember which one.
        List<Map<String, String>> parameters = new java.util.ArrayList<>(List.of(
                // Listed first because it is the one an update silently skips. A stack keeps
                // the parameter values it was created with; changing a default in the
                // template does nothing to a stack that already exists, so leaving this on
                // "Use existing value" produces a change set with no changes at all and the
                // console reports it as "The submitted information didn't contain changes"
                // — which reads as a broken template rather than an unchanged parameter.
                Map.of("name", "EnableInventoryEvents",
                        "value", "true",
                        "why", "Near-real-time resource discovery. Accounts connected before"
                                + " this was switched on still carry false and must be set"
                                + " here by hand — a new default does not reach an existing"
                                + " stack."),
                Map.of("name", "EnableCurReport",
                        "value", "true",
                        "why", "Creates the Cost and Usage Report XamOps reads. Leave at its"
                                + " default unless this account sits under a XamOps payer"
                                + " account, which is already covered by that payer's report."),
                Map.of("name", "EnableCurExport",
                        "value", "false",
                        "why", "CUR 2.0, for BillOps invoice reconciliation. Off by default"
                                + " because it claims the same bucket name as the report above."),
                Map.of("name", "CurS3Bucket",
                        "value", "AUTO",
                        "why", "Which bucket to grant read on. Blank now behaves the same when"
                                + " a report is being created; AUTO states it explicitly.")));

        /*
         * The queue THIS environment listens on.
         *
         * Only listed when it differs from the template's default, which names the shared
         * production queue. An environment with its own queue that leaves this alone
         * onboards accounts forwarding to production, where the events are dropped as
         * belonging to an unknown account — nothing fails, the resources just never appear
         * in under a minute and the messages pile up in a queue nobody reads.
         *
         * New onboardings already carry it automatically; this covers stacks that predate
         * that, where the value is stored on the stack and a template default cannot
         * reach it.
         */
        if (inventoryQueueUrl != null && !inventoryQueueUrl.isBlank()
                && !inventoryQueueUrl.contains(SHARED_QUEUE_NAME)) {
            parameters.add(Map.of("name", "XamOpsInventoryQueueUrl",
                    "value", inventoryQueueUrl.trim(),
                    "why", "This environment has its own inventory queue. Left at the default"
                            + " the account forwards to the shared queue, which this"
                            + " environment does not read."));
            String region = regionFromQueueUrl(inventoryQueueUrl.trim());
            if (region != null) {
                parameters.add(Map.of("name", "XamOpsInventoryRegion",
                        "value", region,
                        "why", "Region of the queue above. They must match or the EventBridge"
                                + " target resolves to a queue that does not exist."));
            }
        }

        // Filtered rather than deep-linked: the update page needs a stack id, and the role
        // stack is region-less IAM that may have been created in any region, so neither the
        // id nor the region is known here.
        String consoleUrl = "https://console.aws.amazon.com/cloudformation/home"
                + "#/stacks?filteringText=XamOps-Connection&filteringStatus=active";

        logger.info("Prepared stack update guide for account {} ({}): template {}, role {}",
                account.getAccountName(), account.getAwsAccountId(), templateUrl, roleName);

        return Map.of(
                "templateUrl", templateUrl,
                "consoleUrl", consoleUrl,
                "likelyStackName", likelyStackName,
                // An account that already has the CUR 2.0 export cannot make this change in
                // one update. CloudFormation creates added resources before deleting removed
                // ones, so the new report's bucket collides with the export's — which still
                // exists at that moment — and the whole update rolls back. Two passes are
                // the only way through, and the warning has to reach the person following
                // the steps rather than sit in a comment here.
                "twoPassWarning", "If this account already has a CUR 2.0 export"
                        + " (aws bcm-data-exports list-exports --region us-east-1 lists one),"
                        + " run the update twice: first with EnableCurReport=false and"
                        + " EnableCurExport=false to release the shared bucket, then again with"
                        + " EnableCurReport=true. A single pass fails on the bucket name and"
                        + " rolls back.",
                "roleName", roleName,
                "parameters", parameters);
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
        return connectViaCallback(externalId, awsAccountId, roleArn, null);
    }

    /**
     * @param stackId ARN of the stack that created the connection, or null from a template
     *                predating this. Recorded even when the connection then fails to verify:
     *                knowing which stack to look at is exactly what a failed connection needs.
     */
    public CloudAccount connectViaCallback(String externalId, String awsAccountId, String roleArn,
                                           String stackId) {
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
        if (stackId != null && !stackId.isBlank()) {
            account.setStackId(stackId);
            account.setStackName(stackNameFrom(stackId));
            account.setStackRegion(stackRegionFrom(stackId));
        }
        awsClientProvider.evictAccount(awsAccountId);

        try {
            Ec2Client testClient = awsClientProvider.getEc2Client(account, configuredRegion);
            testClient.describeRegions();
            boolean reconnected = "FAILED".equals(previousStatus);
            account.setStatus("CONNECTED");
            logger.info("EventBridge callback: successfully connected account externalId={}", externalId);
            CloudAccount saved = cloudAccountRepository.save(account);
            publishConnected(saved, reconnected);
            autoRegisterCur(saved);
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
     * Registers the account's own Cost and Usage Report right after it connects, so the CUR-backed
     * views (charges by service, per-resource cost, data transfer) work without anyone having to
     * trigger a manual refresh later.
     *
     * <p>Best-effort by design. A brand-new account's first CUR delivery can lag the connection by
     * up to ~24h, so registration here will often find no manifest yet and leave the account on
     * Cost Explorer — that is expected, not an error, and must never fail the connection. The
     * account simply becomes CUR-backed the next time registration runs (a later refresh, or the
     * nightly validation job). Only OWN_CUR-style accounts that actually have a conventional
     * {@code xamops-cur-<accountId>} report ever succeed here; member accounts under a payer have
     * no own report and are left untouched, exactly as before.
     */
    private void autoRegisterCur(CloudAccount account) {
        try {
            curAutoRegistrationService.ensureRegistered(account);
            logger.info("[CUR] Auto-registered report for account {} on connect", account.getAwsAccountId());
        } catch (Exception e) {
            // Expected when the first CUR file has not been delivered yet, or the account keeps no
            // own report. Leave it on Cost Explorer; a later refresh picks it up.
            logger.info("[CUR] Auto-registration skipped for account {} on connect: {}",
                    account.getAwsAccountId(), e.getMessage());
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
     * The {@code k8s_workload_*} usage-rollup tables (V20261024_37) are cleared the same way.
     * Their foreign keys to {@code kubernetes_cluster_config} also cascade, but this delete
     * does not depend on that; where Flyway never ran (the local profile) the
     * {@code hasColumn} guard skips them. So is the Spot interruption history
     * ({@code k8s_spot_interruption_event}, V20261024_38), for the same reasons.
     *
     * <p>
     * Runs before the cluster configs are deleted, while the subquery can still resolve
     * them.
     */
    private void deleteClusterAgentData(Long accountId) {
        if (!hasColumn("kubernetes_cluster_config", "cloud_account_id")) {
            return;
        }
        for (String table : List.of("agent_data_cache", "agent_events",
                "k8s_workload_usage_sample", "k8s_workload_usage_hourly", "k8s_workload_usage_daily",
                "k8s_workload_identity", "k8s_spot_interruption_event")) {
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
     *
     * <p>
     * {@code vmss_spot_config} (removed Azure AutoSpotting) is dropped by
     * V20261006_1, but it is listed so rows are still cleared where Flyway is disabled
     * (the local profile) or where an older instance's ddl-auto recreated the table.
     */
    private void deleteOrphanedAccountTables(Long accountId) {
        for (String table : List.of("agent_commands", "rightsizing_history", "rightsizing_recommendations",
                "vmss_spot_config")) {
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

    /**
     * Region segment of an SQS queue URL, or null when it is not the standard shape.
     *
     * <p>{@code https://sqs.<region>.amazonaws.com/<account>/<queue>}. Returning null rather
     * than guessing a default matters: a wrong region here sends events to a queue that does
     * not exist in it, which fails silently at the EventBridge target.
     */
    private String regionFromQueueUrl(String queueUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/")
                .matcher(queueUrl);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Pulls the stack name out of a stack ARN.
     *
     * <p>The ARN is the identity, but the name is what a person sees in the console and what
     * the fallback lookup needs, so both are stored. Shape:
     * {@code arn:aws:cloudformation:<region>:<account>:stack/<name>/<uuid>}.
     */
    private String stackNameFrom(String stackArn) {
        String[] parts = stackArn.split("/");
        return parts.length >= 2 ? parts[1] : null;
    }

    /** Region segment of a stack ARN — {@code DescribeStacks} only sees its own region. */
    private String stackRegionFrom(String stackArn) {
        String[] parts = stackArn.split(":");
        return parts.length >= 4 ? parts[3] : null;
    }
}