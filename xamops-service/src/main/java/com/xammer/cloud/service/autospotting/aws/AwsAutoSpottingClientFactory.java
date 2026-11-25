package com.xammer.cloud.service.autospotting.aws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsPermissionCatalog;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import com.xammer.cloud.permissions.XamOpsExecutionAttributes;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the AutoSpotting AWS clients from the authorized {@link CloudAccount} (design-aws.md §2.2).
 *
 * <p><strong>Identity (Phase A fix).</strong> Credentials come from the stored {@code getRoleArn()}
 * and {@code getExternalId()} — the same pair the read path uses — through STS {@code AssumeRole}
 * with the fixed session name {@link AwsSessionPolicies#SESSION_NAME}. There is no hard-coded ARN and
 * no account-number ExternalId (AWS-04/05).
 *
 * <p><strong>Session policy.</strong> {@code READ} sessions pass the read-only session policy and
 * {@code WRITE} sessions the read-plus-write policy of {@link AwsSessionPolicies}. The effective
 * permission is the intersection of the role policy and the session policy, so a planning-path bug
 * fails with AccessDenied rather than mutating (defense in depth for AC-PLN-01), and the write
 * session can never touch a Kubernetes or Beanstalk group (§8.3 Deny statements).
 *
 * <p><strong>Timeouts and scheduler.</strong> Per core §4.3: 10 s attempt / 30 s call for reads,
 * 15 s / 45 s for writes, standard retry strategy. One shared, bounded
 * {@link ScheduledExecutorService} is passed through {@link ClientOverrideConfiguration}, so the SDK
 * doesn't spin a scheduler per client and per call (the trap {@code AwsClientProvider} documented).
 *
 * <p><strong>Caching.</strong> Clients are cached per (account, region, mode) in a bounded Caffeine
 * LRU whose removal listener closes every evicted {@link AutoCloseable}, so idle clients stop leaking
 * connections. The permission interceptor is reused (the same stamping {@code AwsClientProvider}
 * applies), so AccessDenied surfaces through the existing catalog and maps to {@code PERMISSION}.
 */
@Component
public class AwsAutoSpottingClientFactory implements AwsViewSource, AwsMutatingSource {

    private static final Logger log = LoggerFactory.getLogger(AwsAutoSpottingClientFactory.class);

    /** Read-path timeouts (core §4.3). */
    static final Duration READ_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_CALL_TIMEOUT = Duration.ofSeconds(30);
    /** Write-path timeouts (core §4.3). */
    static final Duration WRITE_ATTEMPT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration WRITE_CALL_TIMEOUT = Duration.ofSeconds(45);

    private final StsClient stsClient;
    private final AwsPermissionInterceptor permissionInterceptor;
    private final ScheduledExecutorService scheduler;
    private final long maxCachedClients;

    /** Per-(account, region, mode) LRU of built clients; evicted clients are closed. */
    private final Cache<String, Object> viewCache;
    private final Cache<String, Object> mutatingCache;
    /** Per (account, mode) assume-role credentials providers, reused across the regional clients. */
    private final Cache<String, AwsCredentialsProvider> credentialsCache;

    public AwsAutoSpottingClientFactory(StsClient stsClient, AwsPermissionInterceptor permissionInterceptor,
            @Value("${autospotting.aws.client-cache.max-clients:256}") long maxCachedClients) {
        this.stsClient = stsClient;
        this.permissionInterceptor = permissionInterceptor;
        this.maxCachedClients = maxCachedClients;
        this.scheduler = Executors.newScheduledThreadPool(2, daemonThreadFactory());
        this.viewCache = closingCache(maxCachedClients);
        this.mutatingCache = closingCache(maxCachedClients);
        this.credentialsCache = Caffeine.newBuilder()
                .maximumSize(maxCachedClients)
                .executor(Runnable::run)
                .removalListener((Object key, AwsCredentialsProvider value, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (value instanceof AutoCloseable c) {
                        closeQuietly(key, c);
                    }
                })
                .build();
        log.info("AwsAutoSpottingClientFactory initialized (max {} cached clients per surface)", maxCachedClients);
    }

    /**
     * A {@code READ}-mode view for the account and region. The returned view exposes only reads; its
     * session policy grants no write action.
     */
    public AwsReadOnlyView readOnlyView(CloudAccount account, String region) {
        String key = cacheKey(account, region, AwsSessionPolicies.Mode.READ);
        return (AwsReadOnlyView) viewCache.get(key, k -> buildView(account, region));
    }

    @Override
    public AwsReadOnlyView view(CloudAccount account, String region) {
        return readOnlyView(account, region);
    }

    /**
     * A {@code WRITE}-mode mutating client for the account and region. Has no caller until Phase 18;
     * built here so the two surfaces share one factory and one session-policy source.
     */
    @Override
    public AwsMutatingClient mutatingClient(CloudAccount account, String region) {
        String key = cacheKey(account, region, AwsSessionPolicies.Mode.WRITE);
        return (AwsMutatingClient) mutatingCache.get(key, k -> buildMutating(account, region));
    }

    private AwsReadOnlyView buildView(CloudAccount account, String region) {
        AwsSessionPolicies.Mode mode = AwsSessionPolicies.Mode.READ;
        AwsCredentialsProvider creds = credentials(account, mode);
        Region r = Region.of(region);
        AutoScalingClient asg = client(AutoScalingClient.builder(), creds, r, mode, account, "AutoScalingClient", region);
        Ec2Client ec2 = client(Ec2Client.builder(), creds, r, mode, account, "Ec2Client", region);
        ElasticLoadBalancingV2Client elbv2 = client(ElasticLoadBalancingV2Client.builder(), creds, r, mode,
                account, "ElasticLoadBalancingV2Client", region);
        CloudFormationClient cfn = client(CloudFormationClient.builder(), creds, r, mode, account,
                "CloudFormationClient", region);
        EventBridgeClient events = client(EventBridgeClient.builder(), creds, r, mode, account,
                "EventBridgeClient", region);
        ServiceQuotasClient quotas = client(ServiceQuotasClient.builder(), creds, r, mode, account,
                "ServiceQuotasClient", region);
        CloudWatchClient cw = client(CloudWatchClient.builder(), creds, r, mode, account, "CloudWatchClient", region);
        EcsClient ecs = client(EcsClient.builder(), creds, r, mode, account, "EcsClient", region);
        // IAM is global; a regional client still works and is keyed by this view.
        IamClient iam = client(IamClient.builder(), creds, Region.AWS_GLOBAL, mode, account, "IamClient", region);
        return new AwsReadOnlyView(region, asg, ec2, elbv2, cfn, events, quotas, cw, ecs, iam);
    }

    private AwsMutatingClient buildMutating(CloudAccount account, String region) {
        AwsSessionPolicies.Mode mode = AwsSessionPolicies.Mode.WRITE;
        AwsCredentialsProvider creds = credentials(account, mode);
        AutoScalingClient asg = client(AutoScalingClient.builder(), creds, Region.of(region), mode,
                account, "AutoScalingClient", region);
        return new AwsMutatingClient(region, asg);
    }

    /**
     * An assume-role credentials provider scoped to the {@code mode} session policy. The provider is
     * cached per (account, mode) so its background refresh thread isn't recreated per client.
     */
    AwsCredentialsProvider credentials(CloudAccount account, AwsSessionPolicies.Mode mode) {
        String roleArn = account.getRoleArn();
        String externalId = account.getExternalId();
        if (roleArn == null || roleArn.isBlank()) {
            throw new IllegalStateException("CloudAccount " + account.getId() + " has no stored role ARN");
        }
        String policy = new AwsSessionPolicies(roleArn).serialize(mode);
        String key = account.getAwsAccountId() + ":" + mode;
        return credentialsCache.get(key, k -> StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(req -> {
                    req.roleArn(roleArn)
                            .roleSessionName(AwsSessionPolicies.SESSION_NAME)
                            .policy(policy);
                    if (externalId != null && !externalId.isBlank()) {
                        req.externalId(externalId);
                    }
                })
                .build());
    }

    /**
     * The override configuration for one client, built from a fresh builder every time. The builder
     * returned by a built {@link ClientOverrideConfiguration}'s {@code toBuilder()} holds its execution
     * attributes as an unmodifiable copy, so adding the per-client IAM prefix to it threw
     * {@code UnsupportedOperationException} for every real client (phase 23 defect D-23-01).
     */
    ClientOverrideConfiguration overrideConfig(CloudAccount account, String region,
            AwsSessionPolicies.Mode mode, String clientSimpleName) {
        boolean write = mode == AwsSessionPolicies.Mode.WRITE;
        ClientOverrideConfiguration.Builder b = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(write ? WRITE_ATTEMPT_TIMEOUT : READ_ATTEMPT_TIMEOUT)
                .apiCallTimeout(write ? WRITE_CALL_TIMEOUT : READ_CALL_TIMEOUT)
                .retryStrategy(RetryMode.STANDARD)
                .scheduledExecutorService(scheduler);
        return withPermissionTracking(b, account, region, clientSimpleName).build();
    }

    /**
     * Stamps the account/service/region attributes and attaches the permission interceptor, the same
     * way {@code AwsClientProvider#withPermissionTracking} does, so AccessDenied surfaces through the
     * existing catalog (§2.2).
     */
    private ClientOverrideConfiguration.Builder withPermissionTracking(ClientOverrideConfiguration.Builder builder,
            CloudAccount account, String region, String clientSimpleName) {
        String accountId = account != null ? account.getAwsAccountId() : null;
        if (accountId == null) {
            return builder;
        }
        return builder
                .putExecutionAttribute(XamOpsExecutionAttributes.ACCOUNT_ID, accountId)
                .putExecutionAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX,
                        AwsPermissionCatalog.iamPrefixForClient(clientSimpleName, null))
                .putExecutionAttribute(XamOpsExecutionAttributes.REGION, region)
                .addExecutionInterceptor(permissionInterceptor);
    }

    private <BuilderT extends AwsClientBuilder<BuilderT, ClientT>, ClientT> ClientT client(
            BuilderT builder, AwsCredentialsProvider creds, Region region, AwsSessionPolicies.Mode mode,
            CloudAccount account, String clientSimpleName, String regionName) {
        return builder
                .credentialsProvider(creds)
                .region(region)
                .overrideConfiguration(overrideConfig(account, regionName, mode, clientSimpleName))
                .build();
    }

    private static String cacheKey(CloudAccount account, String region, AwsSessionPolicies.Mode mode) {
        return account.getAwsAccountId() + ":" + region + ":" + mode;
    }

    private Cache<String, Object> closingCache(long max) {
        return Caffeine.newBuilder()
                .maximumSize(max)
                .executor(Runnable::run) // close on the evicting thread, so eviction and close are not racy
                .removalListener((Object key, Object value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        closeClients(key, value))
                .build();
    }

    /** Close every SDK client held by an evicted cache value (the view holds several). */
    private void closeClients(Object key, Object value) {
        if (value instanceof AwsReadOnlyView view) {
            closeQuietly(key, view.autoScaling());
            closeQuietly(key, view.ec2());
            closeQuietly(key, view.elbv2());
            closeQuietly(key, view.cloudFormation());
            closeQuietly(key, view.eventBridge());
            closeQuietly(key, view.serviceQuotas());
            closeQuietly(key, view.cloudWatch());
            closeQuietly(key, view.ecs());
            closeQuietly(key, view.iam());
        } else if (value instanceof AwsMutatingClient mutating) {
            closeQuietly(key, mutating);
        } else if (value instanceof AutoCloseable c) {
            closeQuietly(key, c);
        }
    }

    private static void closeQuietly(Object key, AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            log.warn("Failed to close evicted AutoSpotting AWS client for {}", key, e);
        }
    }

    /** Test seam: the configured LRU bound. */
    long maxCachedClientsForTest() {
        return maxCachedClients;
    }

    /** Test seam: insert a value so eviction/close can be asserted without wall-clock expiry. */
    void putViewForTest(CloudAccount account, String region, Object value) {
        viewCache.put(cacheKey(account, region, AwsSessionPolicies.Mode.READ), value);
    }

    /** Test seam: force eviction of everything (fires the close listeners). */
    void invalidateAllForTest() {
        viewCache.invalidateAll();
        viewCache.cleanUp();
        mutatingCache.invalidateAll();
        mutatingCache.cleanUp();
        credentialsCache.invalidateAll();
        credentialsCache.cleanUp();
    }

    @PreDestroy
    void shutdown() {
        invalidateAllForTest();
        scheduler.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicLong n = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "autospotting-aws-sdk-sched-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
