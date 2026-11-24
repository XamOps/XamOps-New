package com.xammer.cloud.xampulse.service.setup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Onboarding: turns a validated plan into persisted state and CloudFormation quick-create links, and answers the
 * setup overview. Every method takes the already-authorised {@link CloudAccount}; tenancy is enforced by the
 * controllers through {@code XamPulseAccessGuard} and every repository call here carries the cloud account id.
 */
@Service("xpSetupService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseSetupService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final XpAgentCredentialRepository credentials;
    private final SetupPlanValidator validator;
    private final RetentionPolicyService retention;
    private final BucketSnippetService snippets;
    private final XamPulseTemplateService templates;
    private final ExternalIdService externalIds;
    private final TrustPanelService trust;
    private final CustomerCostEstimator cost;
    private final InstanceCandidateService candidates;
    private final LogSourceService sources;
    private final XamPulseProperties props;
    private final StsClient sts;
    private final AuditService audit;
    private volatile String hostAccountId;

    public XamPulseSetupService(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                                XpAgentCredentialRepository credentials, SetupPlanValidator validator, RetentionPolicyService retention,
                                BucketSnippetService snippets, XamPulseTemplateService templates, ExternalIdService externalIds,
                                TrustPanelService trust, CustomerCostEstimator cost, InstanceCandidateService candidates,
                                LogSourceService sources, XamPulseProperties props, StsClient sts, AuditService audit) {
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
        this.credentials = credentials;
        this.validator = validator;
        this.retention = retention;
        this.snippets = snippets;
        this.templates = templates;
        this.externalIds = externalIds;
        this.trust = trust;
        this.cost = cost;
        this.candidates = candidates;
        this.sources = sources;
        this.props = props;
        this.sts = sts;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public XpSetupOverview overview(CloudAccount account) {
        Optional<XpAccountConfig> cfg = configs.findByCloudAccountId(account.getId());
        XpAccountState state = cfg.map(c -> SetupStateMachine.parse(c.getState())).orElse(XpAccountState.NOT_SET_UP);
        List<XpInstanceCandidate> all = candidates.candidates(account);
        Map<String, XpAccountRegion> rows = regions.findByCloudAccountId(account.getId()).stream()
                .collect(Collectors.toMap(XpAccountRegion::getRegion, r -> r, (a, b) -> a));

        Map<String, List<XpInstanceCandidate>> byRegion = new TreeMap<>();
        all.forEach(c -> byRegion.computeIfAbsent(String.valueOf(c.region()), k -> new ArrayList<>()).add(c));
        rows.keySet().forEach(r -> byRegion.computeIfAbsent(r, k -> new ArrayList<>()));

        List<XpSetupRegion> regionOut = new ArrayList<>();
        for (Map.Entry<String, List<XpInstanceCandidate>> e : byRegion.entrySet()) {
            XpAccountRegion row = rows.get(e.getKey());
            int covered = (int) e.getValue().stream().filter(c -> c.inScope() && "running".equals(c.state())).count();
            int reporting = (int) e.getValue().stream().filter(c -> "ACTIVE".equals(c.agent().status())).count();
            regionOut.add(new XpSetupRegion(e.getKey(), e.getValue().size(), row == null ? "NONE" : stackState(row.getState()),
                    row == null ? "INSTANCES" : row.getTargetMode(), covered, reporting));
        }
        int total = all.size();
        int running = (int) all.stream().filter(c -> "running".equals(c.state())).count();
        int ssm = (int) all.stream().filter(c -> Boolean.TRUE.equals(c.ssmManaged())).count();
        int inScope = (int) all.stream().filter(XpInstanceCandidate::inScope).count();
        int enrolled = (int) all.stream().filter(c -> Set.of("ACTIVE", "STALE").contains(c.agent().status())).count();
        int reporting = (int) all.stream().filter(c -> "ACTIVE".equals(c.agent().status())).count();
        int notCovered = (int) all.stream().filter(c -> "running".equals(c.state()) && !c.inScope()).count();
        int coverage = running == 0 ? 0 : (int) Math.round(100.0 * reporting / running);
        Instant last = all.stream().map(c -> c.agent().lastSeenAt()).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        XpBucketRef bucket = cfg.filter(c -> c.getBucketName() != null)
                .map(c -> new XpBucketRef(c.getBucketName(), c.getBucketRegion(), c.getBucketMode())).orElse(null);
        return new XpSetupOverview(state, false, cfg.map(XpAccountConfig::getHomeRegion).orElse(null), bucket, regionOut,
                new XpSetupInstances(total, ssm, inScope, enrolled, reporting, notCovered), coverage, last,
                archiveStatus(cfg.orElse(null)));
    }

    private XpSetupOverview.XpArchiveStatus archiveStatus(XpAccountConfig c) {
        if (!props.getArchive().isEnabled()) {
            return new XpSetupOverview.XpArchiveStatus("OFF", 0, null, null);
        }
        int failures = c == null ? 0 : c.getArchiveFailures();
        boolean failing = failures >= props.getArchive().getFailureThreshold();
        return new XpSetupOverview.XpArchiveStatus(failing ? "FAILING" : "OK", failures,
                c == null ? null : c.getArchiveLastError(), c == null ? null : c.getArchiveWatermark());
    }

    private static String stackState(String s) {
        return s == null ? "NONE" : switch (s) {
            case "OK", "DEPLOYING", "FAILED" -> s;
            default -> "NONE";
        };
    }

    // ------------------------------------------------------------------ plan

    @Transactional
    public XpSetupPlanResponse plan(CloudAccount account, XpSetupPlanRequest req, ClientUserDetails user, String ip) {
        String aws = account.getAwsAccountId();
        SetupPlanValidator.Validated v = validator.validate(req, aws);
        retention.validateOrThrow(req.retention());
        // Fail before anything is stored if the environment cannot produce working links.
        templates.templateUrl(templates.template(false));
        templates.releaseBaseUrl();
        templates.desiredStateUrl();
        xamopsAccountId();

        XpAccountConfig cfg = configs.findByCloudAccountId(account.getId()).orElseGet(() -> {
            XpAccountConfig c = new XpAccountConfig();
            c.setCloudAccountId(account.getId());
            c.setState(XpAccountState.NOT_SET_UP.name());
            c.setCreatedAt(Instant.now());
            return c;
        });
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        boolean live = EnumSet.of(XpAccountState.DEPLOYING, XpAccountState.VERIFYING, XpAccountState.CONNECTED, XpAccountState.DEGRADED).contains(state);
        if (live && !sameStorage(cfg, req, v.bucketName())) {
            throw XamPulseException.conflict("The bucket, key and home region cannot change while XamPulse is set up."
                    + " Disconnect first, or update retention and regions on the same bucket.");
        }
        // Captured before anything is overwritten: what the deployed stacks were planned with.
        final XpRetention previousRetention = live ? retentionOf(cfg) : null;
        final boolean stackOwnsBucket = "CREATE".equals(req.bucket().mode());

        String kmsMode = kmsParam(req.kms().mode());
        String keyArn = "CUSTOMER_KEY".equals(req.kms().mode()) ? req.kms().keyArn() : null;
        cfg.setClientId(account.getClient() == null ? null : account.getClient().getId());
        cfg.setBucketMode(req.bucket().mode());
        cfg.setBucketName(v.bucketName());
        cfg.setBucketRegion(req.bucket().region());
        cfg.setKmsKeyArn(keyArn);
        cfg.setHomeRegion(req.homeRegion());
        cfg.setStackName(XamPulseTemplateService.stackName(aws));
        cfg.setHotDays(req.retention().hotDays());
        cfg.setIaAfterDays(req.retention().iaAfterDays());
        cfg.setGlacierIrAfterDays(req.retention().glacierIrAfterDays());
        cfg.setExpireAfterDays(req.retention().expireAfterDays());
        cfg.setSettingsJson(settings(kmsMode));
        String externalId = externalIds.getOrCreate(cfg);
        XamPulseTemplateService.Template globalTpl = templates.template(false);
        XamPulseTemplateService.Template regionalTpl = templates.template(true);
        cfg.setTemplateVersion(XamPulseTemplateService.TEMPLATE_VERSION);
        cfg.setTemplateSha256(globalTpl.sha256());
        if (state == XpAccountState.NOT_SET_UP || state == XpAccountState.DISCONNECTED || state == XpAccountState.PLANNED) {
            cfg.setState(SetupStateMachine.move(state, XpAccountState.PLANNED).name());
        }
        cfg.setUpdatedAt(Instant.now());
        cfg = configs.save(cfg);
        if (state == XpAccountState.DISCONNECTED) {
            allowReEnrolment(account.getId());
        }

        // Region rows and stack links.
        List<XpStackLink> stacks = new ArrayList<>();
        Map<String, XpSetupPlanRequest.RegionSelection> byRegion = new LinkedHashMap<>();
        req.regions().forEach(r -> byRegion.put(r.region(), r));
        XpSetupPlanRequest.RegionSelection home = byRegion.get(req.homeRegion());
        Optional<XpAccountRegion> homeLive = live ? liveStack(account.getId(), req.homeRegion()) : Optional.empty();
        Map<String, String> homeBefore = homeLive.map(r -> mutableParams(selectionOf(r), previousRetention, stackOwnsBucket)).orElse(Map.of());
        stacks.add(stackLink(req.homeRegion(), "GLOBAL", cfg.getStackName(), homeLive, templates.templateUrl(globalTpl),
                globalParams(aws, cfg, req, externalId, kmsMode, keyArn, home, v.chunks().getOrDefault(req.homeRegion(), List.of())),
                homeBefore, mutableParams(home, req.retention(), stackOwnsBucket)));
        upsertRegion(account.getId(), req.homeRegion(), "GLOBAL", cfg.getStackName(), globalTpl, home);
        for (XpSetupPlanRequest.RegionSelection r : req.regions()) {
            if (r.region().equals(req.homeRegion())) {
                continue;
            }
            String name = XamPulseTemplateService.regionalStackName(aws, r.region());
            Optional<XpAccountRegion> regLive = live ? liveStack(account.getId(), r.region()) : Optional.empty();
            Map<String, String> regBefore = regLive.map(row -> mutableParams(selectionOf(row), null, false)).orElse(Map.of());
            stacks.add(stackLink(r.region(), "REGIONAL", name, regLive, templates.templateUrl(regionalTpl),
                    regionalParams(r, v.chunks().getOrDefault(r.region(), List.of())), regBefore, mutableParams(r, null, false)));
            upsertRegion(account.getId(), r.region(), "REGIONAL", name, regionalTpl, r);
        }
        if (req.sources() != null) {
            sources.replace(account.getId(), sourceViews(req.sources()));
        } else if (sources.list(account.getId()).isEmpty()) {
            sources.replace(account.getId(), List.of(new XpLogSourceView(null, "SYSTEM", List.of(), null, true, List.of())));
        }

        int instanceCount = v.chunks().values().stream().mapToInt(l -> l.stream().mapToInt(List::size).sum()).sum();
        XpSetupPreview preview = new XpSetupPreview(
                resources(req), List.of(
                "s3:PutObject, s3:GetObject, s3:AbortMultipartUpload on bucket/xamops-xampulse/*",
                "s3:ListBucket with prefix xamops-xampulse/*, s3:GetBucketLocation",
                "kms:GenerateDataKey, kms:Decrypt on the archive key (when one is used)",
                "No s3:DeleteObject. No access key is created."),
                trust.panel(cfg, globalTpl.sha256()).neverCollected(),
                "EXISTING".equals(req.bucket().mode())
                        ? snippets.forExistingBucket(v.bucketName(), aws, templates.resourceSuffix(), keyArn, req.retention()) : null,
                cost.estimate(instanceCount, req.retention(), "CREATE".equals(req.kms().mode())),
                trust.connectionRoleGrants(), globalTpl.sha256(), templates.templateUrl(globalTpl));
        audit.record(user, account.getId(), "setup.plan", "bucket=" + v.bucketName() + " regions=" + byRegion.keySet(), ip);
        return new XpSetupPlanResponse(UUID.randomUUID().toString(), preview, stacks, v.warnings());
    }

    /** Adds or changes one additional region after the account is set up. */
    @Transactional
    public XpStackLink regionPlan(CloudAccount account, String region, XpRegionPlanRequest req, ClientUserDetails user, String ip) {
        if (region == null || !SetupPlanValidator.REGION.matcher(region).matches()) {
            throw XamPulseException.invalid("That is not a valid AWS region.");
        }
        XpAccountConfig cfg = configs.findByCloudAccountId(account.getId()).orElseThrow(XamPulseException::notSetUp);
        if (SetupStateMachine.parse(cfg.getState()) == XpAccountState.NOT_SET_UP || SetupStateMachine.parse(cfg.getState()) == XpAccountState.DISCONNECTED) {
            throw XamPulseException.notSetUp();
        }
        List<String> errors = new ArrayList<>();
        List<List<String>> chunks = validator.target(region, req.targetMode(), req.instanceIds(), req.tagKey(), req.tagValue(),
                req.confirmAllManaged(), errors);
        if (!errors.isEmpty()) {
            throw new XamPulseException("XP_INVALID_REQUEST", 400, errors.get(0), Map.of("errors", errors));
        }
        String aws = account.getAwsAccountId();
        XpSetupPlanRequest.RegionSelection sel = new XpSetupPlanRequest.RegionSelection(region, req.targetMode(), req.instanceIds(),
                req.tagKey(), req.tagValue(), req.confirmAllManaged());
        XpStackLink link;
        Optional<XpAccountRegion> live = liveStack(account.getId(), region);
        if (region.equals(cfg.getHomeRegion())) {
            // The home region's targets live in the global stack: update that stack with the new targets.
            XamPulseTemplateService.Template tpl = templates.template(false);
            Map<String, String> before = live.map(r -> mutableParams(selectionOf(r), null, false)).orElse(Map.of());
            upsertRegion(account.getId(), region, "GLOBAL", cfg.getStackName(), tpl, sel);
            XpSetupPlanRequest asPlan = new XpSetupPlanRequest(
                    new XpSetupPlanRequest.Bucket(cfg.getBucketMode(), cfg.getBucketName(), cfg.getBucketRegion()),
                    new XpSetupPlanRequest.Kms(kmsModeOf(cfg), cfg.getKmsKeyArn()), cfg.getHomeRegion(), List.of(sel), retentionOf(cfg), null);
            link = stackLink(region, "GLOBAL", cfg.getStackName(), live, templates.templateUrl(tpl),
                    globalParams(aws, cfg, asPlan, externalIds.getOrCreate(cfg), kmsParam(kmsModeOf(cfg)), cfg.getKmsKeyArn(), sel, chunks),
                    before, mutableParams(sel, null, false));
        } else {
            XamPulseTemplateService.Template tpl = templates.template(true);
            String name = XamPulseTemplateService.regionalStackName(aws, region);
            Map<String, String> before = live.map(r -> mutableParams(selectionOf(r), null, false)).orElse(Map.of());
            upsertRegion(account.getId(), region, "REGIONAL", name, tpl, sel);
            link = stackLink(region, "REGIONAL", name, live, templates.templateUrl(tpl), regionalParams(sel, chunks),
                    before, mutableParams(sel, null, false));
        }
        audit.record(user, account.getId(), "setup.region-plan", "region=" + region + " mode=" + req.targetMode(), ip);
        return link;
    }

    // ------------------------------------------------------------------ collection control

    @Transactional
    public XpInstanceCandidate updateCollection(CloudAccount account, String instanceId, XpCollectionUpdate update,
                                                ClientUserDetails user, String ip) {
        if (instanceId == null || !SetupPlanValidator.INSTANCE_ID.matcher(instanceId).matches()) {
            throw XamPulseException.notFound();
        }
        if (update == null || (update.level() != null && !Set.of("T0", "T1", "T2").contains(update.level()))) {
            throw XamPulseException.invalid("level must be T0, T1 or T2.");
        }
        if (update.sources() != null && !update.sources().isEmpty()) {
            throw XamPulseException.invalid("Per-instance source selection is not available. Edit the account's sources instead.");
        }
        XpAgent agent = agents.findByCloudAccountIdAndInstanceId(account.getId(), instanceId).orElseThrow(XamPulseException::notFound);
        if ("REVOKED".equals(agent.getState())) {
            throw XamPulseException.conflict("This instance was disconnected. Plan the setup again to cover it.");
        }
        if (!update.enabled()) {
            agent.setState("PAUSED");
            agent.setPendingCommand("UNINSTALL");
            revokeCredentials(agent.getId());
        } else if ("PAUSED".equals(agent.getState())) {
            agent.setState("PENDING"); // may enrol again at the next install-document run
            agent.setPendingCommand(null);
        }
        if (update.level() != null && !update.level().equals(agent.getCollectionLevel())) {
            agent.setCollectionLevel(update.level());
            agent.setConfigVersion(agent.getConfigVersion() + 1);
            if (update.enabled() && !"UNINSTALL".equals(agent.getPendingCommand())) {
                agent.setPendingCommand("RELOAD_CONFIG");
            }
        }
        agents.save(agent);
        audit.record(user, account.getId(), "setup.collection", instanceId + " enabled=" + update.enabled() + " level=" + update.level(), ip);
        return candidates.candidates(account).stream().filter(c -> c.instanceId().equals(instanceId)).findFirst()
                .orElseThrow(XamPulseException::notFound);
    }

    // ------------------------------------------------------------------ helpers

    void revokeCredentials(Long agentId) {
        credentials.findByAgentId(agentId).forEach(c -> {
            if (c.getRevokedAt() == null) {
                c.setRevokedAt(Instant.now());
                credentials.save(c);
            }
        });
    }

    private void allowReEnrolment(Long accountId) {
        for (XpAgent a : agents.findByCloudAccountId(accountId)) {
            if ("REVOKED".equals(a.getState())) {
                a.setState("PENDING");
                a.setPendingCommand(null);
                agents.save(a);
            }
        }
    }

    private void upsertRegion(Long accountId, String region, String kind, String stackName,
                              XamPulseTemplateService.Template tpl, XpSetupPlanRequest.RegionSelection sel) {
        XpAccountRegion row = regions.findByCloudAccountIdAndRegion(accountId, region).orElseGet(() -> {
            XpAccountRegion r = new XpAccountRegion();
            r.setCloudAccountId(accountId);
            r.setRegion(region);
            r.setState("NONE");
            return r;
        });
        row.setKind(kind);
        row.setStackName(stackName);
        row.setTemplateVersion(XamPulseTemplateService.TEMPLATE_VERSION);
        row.setTemplateSha256(tpl.sha256());
        if (sel != null) {
            row.setTargetMode(sel.targetMode());
            row.setTargetInstanceIdsJson("INSTANCES".equals(sel.targetMode()) && sel.instanceIds() != null ? write(new ArrayList<>(new LinkedHashSet<>(sel.instanceIds()))) : null);
            row.setObserveTagKey("TAG".equals(sel.targetMode()) ? sel.tagKey() : null);
            row.setObserveTagValue("TAG".equals(sel.targetMode()) ? (sel.tagValue() == null || sel.tagValue().isEmpty() ? "true" : sel.tagValue()) : null);
        } else if (row.getTargetMode() == null) {
            row.setTargetMode("INSTANCES");
        }
        regions.save(row);
    }

    Map<String, String> globalParams(String awsAccountId, XpAccountConfig cfg, XpSetupPlanRequest req, String externalId, String kmsMode,
                                     String keyArn, XpSetupPlanRequest.RegionSelection home, List<List<String>> homeChunks) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("XamOpsAccountId", xamopsAccountId());
        p.put("XamOpsPrincipalRoleName", props.getSetup().getPrincipalRoleName());
        p.put("PulseExternalId", externalId);
        p.put("BucketMode", req.bucket().mode());
        p.put("BucketName", cfg.getBucketName());
        p.put("KmsMode", kmsMode);
        p.put("KmsKeyArn", keyArn == null ? "" : keyArn);
        p.put("IncludeRegionalResources", home == null ? "false" : "true");
        p.putAll(targetParams(home, homeChunks));
        p.put("ReleaseBaseUrl", templates.releaseBaseUrl());
        p.put("DesiredStateUrl", templates.desiredStateUrl());
        if ("CREATE".equals(req.bucket().mode())) {
            p.putAll(retention.createModeParameters(req.retention()));
        }
        return p;
    }

    Map<String, String> regionalParams(XpSetupPlanRequest.RegionSelection sel, List<List<String>> chunks) {
        Map<String, String> p = new LinkedHashMap<>(targetParams(sel, chunks));
        p.put("ReleaseBaseUrl", templates.releaseBaseUrl());
        p.put("DesiredStateUrl", templates.desiredStateUrl());
        return p;
    }

    static Map<String, String> targetParams(XpSetupPlanRequest.RegionSelection sel, List<List<String>> chunks) {
        Map<String, String> p = new LinkedHashMap<>();
        if (sel == null) {
            p.put("TargetMode", "InstanceIds");
            return p;
        }
        switch (sel.targetMode()) {
            case "ALL_MANAGED" -> p.put("TargetMode", "AllManaged");
            case "TAG" -> {
                p.put("TargetMode", "Tag");
                p.put("ObserveTagKey", sel.tagKey());
                p.put("ObserveTagValue", sel.tagValue() == null || sel.tagValue().isEmpty() ? "true" : sel.tagValue());
            }
            default -> {
                p.put("TargetMode", "InstanceIds");
                for (int i = 0; i < chunks.size() && i < 10; i++) {
                    p.put(i == 0 ? "InstanceIds" : "InstanceIds" + (i + 1), String.join(",", chunks.get(i)));
                }
            }
        }
        return p;
    }

    private static boolean sameStorage(XpAccountConfig cfg, XpSetupPlanRequest req, String resolvedName) {
        return Objects.equals(cfg.getBucketName(), resolvedName) && Objects.equals(cfg.getBucketMode(), req.bucket().mode())
                && Objects.equals(cfg.getHomeRegion(), req.homeRegion()) && Objects.equals(cfg.getBucketRegion(), req.bucket().region())
                && Objects.equals(cfg.getKmsKeyArn(), "CUSTOMER_KEY".equals(req.kms().mode()) ? req.kms().keyArn() : null)
                && Objects.equals(kmsModeOf(cfg), req.kms().mode());
    }

    static String kmsParam(String apiMode) {
        return switch (apiMode) {
            case "CUSTOMER_KEY" -> "CustomerKey";
            case "AWS_MANAGED" -> "AwsManaged";
            default -> "CreateKey";
        };
    }

    private static String settings(String kmsParam) {
        return write(Map.of("kmsMode", kmsParam));
    }

    /** API-style KMS mode recovered from the stored settings. */
    static String kmsModeOf(XpAccountConfig cfg) {
        try {
            Map<String, String> m = JSON.readValue(cfg.getSettingsJson() == null ? "{}" : cfg.getSettingsJson(), new TypeReference<Map<String, String>>() {});
            return switch (m.getOrDefault("kmsMode", "CreateKey")) {
                case "CustomerKey" -> "CUSTOMER_KEY";
                case "AwsManaged" -> "AWS_MANAGED";
                default -> "CREATE";
            };
        } catch (Exception e) {
            return "CREATE";
        }
    }

    private static XpRetention retentionOf(XpAccountConfig c) {
        return new XpRetention(c.getHotDays(), c.getIaAfterDays(), c.getGlacierIrAfterDays(), c.getExpireAfterDays());
    }

    // ------------------------------------------------------------------ create vs update links

    /** The region's stack exists and can be updated (the watcher last saw CREATE/UPDATE_COMPLETE), or empty. */
    private Optional<XpAccountRegion> liveStack(Long accountId, String region) {
        return regions.findByCloudAccountIdAndRegion(accountId, region).filter(r -> "OK".equals(r.getState()));
    }

    /**
     * The parameters that change between deployments: targeting, plus lifecycle days when the stack owns the bucket.
     * Everything else (bucket, key, home region, external id, URLs) is fixed while the account is set up.
     */
    private Map<String, String> mutableParams(XpSetupPlanRequest.RegionSelection sel, XpRetention ret, boolean stackOwnsBucket) {
        Map<String, String> p = new LinkedHashMap<>(targetParams(sel, sel == null ? List.of() : chunk(sel.instanceIds())));
        if (stackOwnsBucket && ret != null) {
            p.putAll(retention.createModeParameters(ret));
        }
        return p;
    }

    /** What the deployed stack was planned with, read from the region row before it is overwritten. */
    private static XpSetupPlanRequest.RegionSelection selectionOf(XpAccountRegion row) {
        List<String> ids = new ArrayList<>();
        if (row.getTargetInstanceIdsJson() != null && !row.getTargetInstanceIdsJson().isBlank()) {
            try {
                ids = JSON.readValue(row.getTargetInstanceIdsJson(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                ids = new ArrayList<>(InstanceCandidateService.targetIds(row));
            }
        }
        return new XpSetupPlanRequest.RegionSelection(row.getRegion(), row.getTargetMode() == null ? "INSTANCES" : row.getTargetMode(),
                ids, row.getObserveTagKey(), row.getObserveTagValue(), Boolean.TRUE);
    }

    /** Same grouping as the validator (de-duplicated, insertion order, 50 per chunk). */
    private static List<List<String>> chunk(List<String> ids) {
        return ids == null ? List.of() : SetupPlanValidator.chunk(new ArrayList<>(new LinkedHashSet<>(ids)));
    }

    /** Parameters whose value differs; an instance-id chunk that is no longer needed is cleared with "". */
    static Map<String, String> changedParams(Map<String, String> before, Map<String, String> after) {
        Map<String, String> out = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            if (!Objects.equals(before.get(k), v)) {
                out.put(k, v);
            }
        });
        before.keySet().stream().filter(k -> k.startsWith("InstanceIds") && !after.containsKey(k)).forEach(k -> out.put(k, ""));
        return out;
    }

    /**
     * Quick-create link when the stack does not exist; otherwise the console's Update stack link plus the parameters
     * the customer must change ({@code before} is what the stack was deployed with, {@code after} the new plan).
     */
    private XpStackLink stackLink(String region, String kind, String stackName, Optional<XpAccountRegion> live, String templateUrl,
                                  Map<String, String> createParams, Map<String, String> before, Map<String, String> after) {
        if (live.isPresent()) {
            String id = live.get().getStackId() != null && !live.get().getStackId().isBlank() ? live.get().getStackId() : stackName;
            return XpStackLink.update(region, kind, stackName, templates.updateStackUrl(region, id), changedParams(before, after));
        }
        return XpStackLink.create(region, kind, stackName, templates.quickCreateUrl(region, templateUrl, stackName, createParams));
    }

    private static List<XpLogSourceView> sourceViews(XpSetupPlanRequest.Sources s) {
        List<XpLogSourceView> out = new ArrayList<>();
        out.add(new XpLogSourceView(null, "SYSTEM", List.of(), null, s.system(), List.of()));
        out.add(new XpLogSourceView(null, "DOCKER", List.of(), null, s.docker(), List.of()));
        if (s.custom() != null) {
            for (XpSetupPlanRequest.CustomSource c : s.custom()) {
                out.add(new XpLogSourceView(null, "CUSTOM", List.of(c.path()), c.parser(), true, List.of()));
            }
        }
        return out;
    }

    private static List<String> resources(XpSetupPlanRequest req) {
        List<String> r = new ArrayList<>();
        r.add("IAM role XamOpsXamPulseArchiveRole (trusts the XamOps role, requires your ExternalId, cannot delete objects)");
        if ("CREATE".equals(req.bucket().mode())) {
            r.add("S3 bucket (private, versioned, SSE-KMS, lifecycle rules for xamops-xampulse/)");
            r.add("S3 bucket policy (TLS only, encrypted writes, archive prefix readable only by the archive role and account root)");
            if ("CREATE".equals(req.kms().mode())) {
                r.add("KMS key and alias (rotation on)");
            }
        } else {
            r.add("Nothing on your existing bucket. Apply the snippets below yourself.");
        }
        r.add("Systems Manager document XamOps-XamPulse-Install-Linux and an association that runs it every 30 minutes on the instances you chose");
        return r;
    }

    private String xamopsAccountId() {
        String configured = props.getSetup().getXamopsAccountId();
        if (configured != null && configured.matches("^\\d{12}$")) {
            return configured;
        }
        String cached = hostAccountId;
        if (cached == null) {
            try {
                cached = sts.getCallerIdentity().account();
            } catch (RuntimeException e) {
                throw XamPulseException.conflict("The XamOps AWS account id is not configured (xampulse.setup.xamops-account-id).");
            }
            hostAccountId = cached;
        }
        return cached;
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Heartbeat younger than this counts as reporting. */
    public static final Duration REPORTING_WINDOW = InstanceCandidateService.STALE_AFTER;
}
