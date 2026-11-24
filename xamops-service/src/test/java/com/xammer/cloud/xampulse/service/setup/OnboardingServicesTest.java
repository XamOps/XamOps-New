package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Plan generation, region plans, collection control, the stack watcher and the checklist, with in-memory repositories. */
class OnboardingServicesTest {

    private static final String AWS = "123456789012";
    private final XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
    private final XpAccountRegionRepository regions = mock(XpAccountRegionRepository.class);
    private final XpAgentRepository agents = mock(XpAgentRepository.class);
    private final XpAgentCredentialRepository credentials = mock(XpAgentCredentialRepository.class);
    private final LogSourceService sources = mock(LogSourceService.class);
    private final InstanceCandidateService candidates = mock(InstanceCandidateService.class);
    private final StsClient sts = mock(StsClient.class);
    private final Map<String, XpAccountRegion> regionRows = new HashMap<>();
    private XpAccountConfig stored;
    private XamPulseSetupService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        XamPulseProperties props = new XamPulseProperties();
        props.getSetup().setTemplateBaseUrl("https://templates.example.com/xampulse");
        props.getSetup().setApiBaseUrl("https://live.example.com");
        props.getSetup().setReleaseBaseUrl("https://releases.example.com/xampulse");
        props.getSetup().setXamopsAccountId("111122223333");
        XamPulseTemplateService templates = new XamPulseTemplateService(props);
        SetupPlanValidator validator = new SetupPlanValidator();
        RetentionPolicyService retention = new RetentionPolicyService(validator);
        service = new XamPulseSetupService(configs, regions, agents, credentials, validator, retention, new BucketSnippetService(retention),
                templates, new ExternalIdService(), new TrustPanelService(props), new CustomerCostEstimator(), candidates, sources, props, sts,
                mock(AuditService.class));
        account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId(AWS);
        when(configs.findByCloudAccountId(7L)).thenAnswer(i -> Optional.ofNullable(stored));
        when(configs.save(any())).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });
        when(regions.findByCloudAccountIdAndRegion(any(), any())).thenAnswer(i -> Optional.ofNullable(regionRows.get((String) i.getArgument(1))));
        when(regions.findByCloudAccountId(7L)).thenAnswer(i -> new ArrayList<>(regionRows.values()));
        when(regions.save(any())).thenAnswer(i -> {
            XpAccountRegion r = i.getArgument(0);
            regionRows.put(r.getRegion(), r);
            return r;
        });
        when(sources.list(7L)).thenReturn(List.of());
    }

    private static List<String> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> String.format("i-%017x", i + 1)).toList();
    }

    private XpSetupPlanRequest planCreate(XpSetupPlanRequest.RegionSelection... sel) {
        return new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("CREATE", null, "ap-south-1"), new XpSetupPlanRequest.Kms("CREATE", null),
                "ap-south-1", List.of(sel), new XpRetention(14, 30, 90, 365), null);
    }

    private static XpSetupPlanRequest.RegionSelection inst(String region, int n) {
        return new XpSetupPlanRequest.RegionSelection(region, "INSTANCES", ids(n), null, null, null);
    }

    private static Map<String, String> params(String url) {
        Map<String, String> out = new HashMap<>();
        String q = url.substring(url.indexOf('?', url.indexOf('#')) + 1);
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            out.put(kv.substring(0, eq), java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    // ------------------------------------------------------------------ plan

    @Test
    void aPlanPersistsPlannedStateAndBuildsOneLinkPerStack() {
        XpSetupPlanResponse res = service.plan(account, planCreate(inst("ap-south-1", 2), inst("eu-west-1", 60),
                new XpSetupPlanRequest.RegionSelection("us-east-1", "TAG", null, "xamops:observe", "true", null)), null, "1.1.1.1");

        assertEquals("PLANNED", stored.getState());
        assertEquals("xamops-xampulse-123456789012-ap-south-1", stored.getBucketName());
        assertEquals("CREATE", stored.getBucketMode());
        assertEquals("XamOpsXamPulse-123456789012", stored.getStackName());
        assertEquals(14, stored.getHotDays());
        assertNotNull(stored.getArchiveExternalIdEnc());
        assertEquals(3, res.stacks().size());

        XpStackLink global = res.stacks().get(0);
        assertEquals("GLOBAL", global.kind());
        assertEquals("ap-south-1", global.region());
        Map<String, String> g = params(global.quickCreateUrl());
        assertEquals("https://templates.example.com/xampulse/xamops-xampulse.yml", g.get("templateURL"));
        assertEquals("XamOpsXamPulse-123456789012", g.get("stackName"));
        assertEquals("111122223333", g.get("param_XamOpsAccountId"));
        assertEquals("CREATE", g.get("param_BucketMode"));
        assertEquals("CreateKey", g.get("param_KmsMode"));
        assertEquals("true", g.get("param_IncludeRegionalResources"));
        assertEquals("InstanceIds", g.get("param_TargetMode"));
        assertEquals(String.join(",", ids(2)), g.get("param_InstanceIds"));
        assertEquals("30", g.get("param_IaAfterDays"));
        assertEquals("90", g.get("param_GlacierIrAfterDays"));
        assertEquals("365", g.get("param_ExpireAfterDays"));
        assertEquals(stored.getArchiveExternalIdEnc(), g.get("param_PulseExternalId"));
        assertEquals("https://live.example.com/api/v1/agent/xampulse/desired-state", g.get("param_DesiredStateUrl"));

        XpStackLink eu = res.stacks().get(1);
        assertEquals("REGIONAL", eu.kind());
        assertEquals("XamOpsXamPulseRegion-123456789012-eu-west-1", eu.stackName());
        Map<String, String> e = params(eu.quickCreateUrl());
        assertEquals("https://templates.example.com/xampulse/xamops-xampulse-region.yml", e.get("templateURL"));
        assertEquals(50, e.get("param_InstanceIds").split(",").length, "first chunk holds 50 ids");
        assertEquals(10, e.get("param_InstanceIds2").split(",").length);
        assertNull(e.get("param_PulseExternalId"), "regional stacks carry no secret");
        assertNull(e.get("param_BucketName"));

        Map<String, String> u = params(res.stacks().get(2).quickCreateUrl());
        assertEquals("Tag", u.get("param_TargetMode"));
        assertEquals("xamops:observe", u.get("param_ObserveTagKey"));

        assertEquals("GLOBAL", regionRows.get("ap-south-1").getKind());
        assertEquals("REGIONAL", regionRows.get("eu-west-1").getKind());
        assertEquals("TAG", regionRows.get("us-east-1").getTargetMode());
        assertEquals("NONE", regionRows.get("eu-west-1").getState());
        assertEquals(res.preview().templateSha256(), regionRows.get("ap-south-1").getTemplateSha256());
    }

    @Test
    void theExternalIdAppearsOnlyInsideTheQuickCreateLink() {
        XpSetupPlanResponse res = service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        String secret = stored.getArchiveExternalIdEnc();
        assertTrue(res.stacks().get(0).quickCreateUrl().contains(secret));
        assertFalse(res.preview().toString().contains(secret));
        assertFalse(res.warnings().toString().contains(secret));
    }

    @Test
    void theExternalIdIsKeptAcrossReplans() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        String first = stored.getArchiveExternalIdEnc();
        service.plan(account, planCreate(inst("ap-south-1", 3)), null, "ip");
        assertEquals(first, stored.getArchiveExternalIdEnc());
    }

    @Test
    void withoutAHomeRegionSelectionTheGlobalStackSkipsRegionalResources() {
        XpSetupPlanResponse res = service.plan(account, planCreate(inst("eu-west-1", 1)), null, "ip");
        Map<String, String> g = params(res.stacks().get(0).quickCreateUrl());
        assertEquals("false", g.get("param_IncludeRegionalResources"));
        assertNull(g.get("param_InstanceIds"));
    }

    @Test
    void existingBucketPlansCarryCustomerSnippetsAndNoRetentionParameters() {
        XpSetupPlanRequest req = new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("EXISTING", "company-logs", "ap-south-1"),
                new XpSetupPlanRequest.Kms("CUSTOMER_KEY", "arn:aws:kms:ap-south-1:123456789012:key/abc-123"), "ap-south-1",
                List.of(inst("ap-south-1", 1)), new XpRetention(14, 30, 90, 365), null);
        XpSetupPlanResponse res = service.plan(account, req, null, "ip");
        assertNotNull(res.preview().customerSnippets());
        assertNotNull(res.preview().customerSnippets().kmsGrant());
        assertTrue(res.preview().customerSnippets().bucketPolicy().contains("company-logs"));
        Map<String, String> g = params(res.stacks().get(0).quickCreateUrl());
        assertEquals("EXISTING", g.get("param_BucketMode"));
        assertEquals("CustomerKey", g.get("param_KmsMode"));
        assertEquals("arn:aws:kms:ap-south-1:123456789012:key/abc-123", g.get("param_KmsKeyArn"));
        assertNull(g.get("param_IaAfterDays"), "an existing bucket's lifecycle is the customer's");
        assertTrue(res.preview().resources().stream().anyMatch(r -> r.contains("Nothing on your existing bucket")));
    }

    @Test
    void thePreviewShowsGrantsHashAndCostBeforeDeploy() {
        XpSetupPlanResponse res = service.plan(account, planCreate(inst("ap-south-1", 10)), null, "ip");
        assertEquals(64, res.preview().templateSha256().length());
        assertEquals("https://templates.example.com/xampulse/xamops-xampulse.yml", res.preview().templateUrl());
        assertTrue(res.preview().iamActions().stream().anyMatch(a -> a.contains("No s3:DeleteObject")));
        assertFalse(res.preview().connectionRoleGrants().isEmpty());
        assertTrue(res.preview().customerCostEstimate().s3Usd() > 0);
        assertEquals(1.0, res.preview().customerCostEstimate().kmsUsd());
        assertFalse(res.preview().customerCostEstimate().notes().isEmpty());
        assertNull(res.preview().customerSnippets());
    }

    @Test
    void invalidPlansPersistNothing() {
        assertThrows(XamPulseException.class, () -> service.plan(account,
                new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("CREATE", null, "ap-south-1"), new XpSetupPlanRequest.Kms("CREATE", null), "ap-south-1",
                        List.of(inst("ap-south-1", 1)), new XpRetention(1, 1, 1, 1), null), null, "ip"));
        verify(configs, never()).save(any());
        verify(regions, never()).save(any());
    }

    @Test
    void templateHostingMustBeConfiguredBeforeAnythingIsStored() {
        XamPulseProperties p = new XamPulseProperties();
        p.getSetup().setXamopsAccountId("111122223333");
        SetupPlanValidator v = new SetupPlanValidator();
        RetentionPolicyService r = new RetentionPolicyService(v);
        XamPulseSetupService s = new XamPulseSetupService(configs, regions, agents, credentials, v, r, new BucketSnippetService(r),
                new XamPulseTemplateService(p), new ExternalIdService(), new TrustPanelService(p), new CustomerCostEstimator(), candidates, sources, p, sts,
                mock(AuditService.class));
        assertThrows(XamPulseException.class, () -> s.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip"));
        verify(configs, never()).save(any());
    }

    @Test
    void theBucketCannotChangeWhileSetUpButRegionsAndRetentionCan() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        stored.setState("CONNECTED");
        XpSetupPlanRequest other = new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("EXISTING", "other-bucket", "ap-south-1"),
                new XpSetupPlanRequest.Kms("AWS_MANAGED", null), "ap-south-1", List.of(inst("ap-south-1", 1)), new XpRetention(14, 30, 90, 365), null);
        XamPulseException e = assertThrows(XamPulseException.class, () -> service.plan(account, other, null, "ip"));
        assertEquals("XP_STATE_CONFLICT", e.getCode());

        service.plan(account, new XpSetupPlanRequest(planCreate().bucket(), planCreate().kms(), "ap-south-1",
                List.of(inst("ap-south-1", 2), inst("eu-west-1", 1)), new XpRetention(21, 45, 120, 400), null), null, "ip");
        assertEquals("CONNECTED", stored.getState(), "re-planning a live account keeps its state");
        assertEquals(21, stored.getHotDays());
        assertTrue(regionRows.containsKey("eu-west-1"));
    }

    @Test
    void replanningAfterDisconnectAllowsAgentsToEnrolAgain() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        stored.setState("DISCONNECTED");
        XpAgent revoked = new XpAgent();
        revoked.setState("REVOKED");
        revoked.setPendingCommand("UNINSTALL");
        XpAgent paused = new XpAgent();
        paused.setState("PAUSED");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(revoked, paused));
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        assertEquals("PLANNED", stored.getState());
        assertEquals("PENDING", revoked.getState());
        assertNull(revoked.getPendingCommand());
        assertEquals("PAUSED", paused.getState(), "an admin's pause survives a reconnect");
    }

    // ------------------------------------------------------------------ region plans

    @Test
    void addingARegionAfterSetupGivesAStackLinkAndRemembersTheTargets() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        XpStackLink link = service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("INSTANCES", ids(3), null, null, null), null, "ip");
        assertEquals("REGIONAL", link.kind());
        assertEquals("XamOpsXamPulseRegion-123456789012-us-west-2", link.stackName());
        assertEquals(3, params(link.quickCreateUrl()).get("param_InstanceIds").split(",").length);
        assertEquals("INSTANCES", regionRows.get("us-west-2").getTargetMode());
    }

    @Test
    void changingTheHomeRegionTargetsRegeneratesTheGlobalStackLink() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        XpStackLink link = service.regionPlan(account, "ap-south-1", new XpRegionPlanRequest("ALL_MANAGED", null, null, null, true), null, "ip");
        assertEquals("GLOBAL", link.kind());
        Map<String, String> p = params(link.quickCreateUrl());
        assertEquals("AllManaged", p.get("param_TargetMode"));
        assertEquals("CREATE", p.get("param_BucketMode"));
        assertEquals(stored.getArchiveExternalIdEnc(), p.get("param_PulseExternalId"));
    }

    /** Simulates the stack watcher having seen the stack complete. */
    private void stackIsLive(String region, String stackId) {
        regionRows.get(region).setState("OK");
        regionRows.get(region).setStackId(stackId);
        stored.setState("VERIFYING");
    }

    @Test
    void replanningALiveAccountGivesAnUpdateLinkListingOnlyTheChangedParameters() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        stackIsLive("ap-south-1", "arn:aws:cloudformation:ap-south-1:123456789012:stack/XamOpsXamPulse-123456789012/abc");

        XpSetupPlanRequest.RegionSelection other = new XpSetupPlanRequest.RegionSelection("ap-south-1", "INSTANCES",
                List.of("i-0aaaaaaaaaaaaaaaa"), null, null, null);
        XpStackLink link = service.plan(account, planCreate(other), null, "ip").stacks().get(0);

        assertEquals("UPDATE", link.action());
        assertNull(link.quickCreateUrl(), "quick-create would fail for an existing stack");
        assertTrue(link.updateUrl().contains("#/stacks/update/template?stackId=arn%3Aaws%3Acloudformation"), link.updateUrl());
        assertEquals(Map.of("InstanceIds", "i-0aaaaaaaaaaaaaaaa"), link.parameters());
        assertEquals("[\"i-0aaaaaaaaaaaaaaaa\"]", regionRows.get("ap-south-1").getTargetInstanceIdsJson());
    }

    @Test
    void anUnchangedReplanListsNoParametersAndARetentionChangeListsTheLifecycleDays() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        stackIsLive("ap-south-1", "sid");
        assertEquals(Map.of(), service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip").stacks().get(0).parameters());

        XpSetupPlanRequest longer = new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("CREATE", null, "ap-south-1"),
                new XpSetupPlanRequest.Kms("CREATE", null), "ap-south-1", List.of(inst("ap-south-1", 1)), new XpRetention(14, 30, 90, 730), null);
        assertEquals(Map.of("ExpireAfterDays", "730"), service.plan(account, longer, null, "ip").stacks().get(0).parameters());
    }

    @Test
    void shrinkingTheInstanceListClearsTheUnusedChunkAndSwitchingToTagsSetsTheTagParameters() {
        service.plan(account, planCreate(inst("ap-south-1", 60)), null, "ip");
        stackIsLive("ap-south-1", "sid");
        Map<String, String> p = service.plan(account, planCreate(inst("ap-south-1", 3)), null, "ip").stacks().get(0).parameters();
        assertEquals("", p.get("InstanceIds2"), "the second chunk must be cleared");
        assertEquals(3, p.get("InstanceIds").split(",").length);

        XpStackLink tag = service.regionPlan(account, "ap-south-1", new XpRegionPlanRequest("TAG", null, "team", "obs", null), null, "ip");
        assertEquals("UPDATE", tag.action());
        assertEquals("Tag", tag.parameters().get("TargetMode"));
        assertEquals("team", tag.parameters().get("ObserveTagKey"));
        assertEquals("obs", tag.parameters().get("ObserveTagValue"));
        assertEquals("", tag.parameters().get("InstanceIds"));
    }

    @Test
    void aRegionWhoseStackIsNotLiveStillGetsAQuickCreateLink() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        stackIsLive("ap-south-1", "sid");
        XpStackLink link = service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("INSTANCES", ids(2), null, null, null), null, "ip");
        assertEquals("CREATE", link.action());
        assertNotNull(link.quickCreateUrl());
        assertNull(link.parameters());
    }

    @Test
    void regionPlansNeedASetUpAccountAndValidTargets() {
        assertThrows(XamPulseException.class, () -> service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("INSTANCES", ids(1), null, null, null), null, "ip"));
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        assertThrows(XamPulseException.class, () -> service.regionPlan(account, "not a region", new XpRegionPlanRequest("INSTANCES", ids(1), null, null, null), null, "ip"));
        assertThrows(XamPulseException.class, () -> service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("ALL_MANAGED", null, null, null, false), null, "ip"));
        assertThrows(XamPulseException.class, () -> service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("INSTANCES", List.of("bad"), null, null, null), null, "ip"));
        stored.setState("DISCONNECTED");
        assertThrows(XamPulseException.class, () -> service.regionPlan(account, "us-west-2", new XpRegionPlanRequest("INSTANCES", ids(1), null, null, null), null, "ip"));
    }

    // ------------------------------------------------------------------ collection control

    private XpAgent liveAgent() {
        XpAgent a = new XpAgent();
        a.setId(5L);
        a.setCloudAccountId(7L);
        a.setInstanceId(ids(1).get(0));
        a.setState("ACTIVE");
        a.setConfigVersion(1);
        a.setCollectionLevel("T1");
        when(agents.findByCloudAccountIdAndInstanceId(7L, a.getInstanceId())).thenReturn(Optional.of(a));
        when(candidates.candidates(account)).thenReturn(List.of(new XpInstanceCandidate(a.getInstanceId(), "web", "ap-south-1", null, "running", Map.of(),
                true, true, "T1", new XpAgentInfo(null, null, "ACTIVE"))));
        return a;
    }

    @Test
    void pausingRevokesCredentialsAndQueuesAnUninstallThatDesiredStateWillKeep() {
        XpAgent a = liveAgent();
        var cred = new com.xammer.cloud.xampulse.domain.XpAgentCredential();
        cred.setAgentId(5L);
        when(credentials.findByAgentId(5L)).thenReturn(List.of(cred));
        service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(false, "T1", null), null, "ip");
        assertEquals("PAUSED", a.getState());
        assertEquals("UNINSTALL", a.getPendingCommand());
        assertNotNull(cred.getRevokedAt());
        service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(true, "T1", null), null, "ip");
        assertEquals("PENDING", a.getState(), "resumed: may enrol again at the next install run");
        assertNull(a.getPendingCommand());
    }

    @Test
    void changingTheLevelBumpsTheConfigVersionAndAsksTheAgentToReload() {
        XpAgent a = liveAgent();
        service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(true, "T2", null), null, "ip");
        assertEquals("T2", a.getCollectionLevel());
        assertEquals(2, a.getConfigVersion());
        assertEquals("RELOAD_CONFIG", a.getPendingCommand());
    }

    @Test
    void collectionUpdatesRejectBadInput() {
        XpAgent a = liveAgent();
        assertThrows(XamPulseException.class, () -> service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(true, "T9", null), null, "ip"));
        assertThrows(XamPulseException.class, () -> service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(true, "T1", List.of("nginx")), null, "ip"));
        assertThrows(XamPulseException.class, () -> service.updateCollection(account, "not-an-instance", new XpCollectionUpdate(true, "T1", null), null, "ip"));
        assertThrows(XamPulseException.class, () -> service.updateCollection(account, "i-0aaaaaaaaaaaaaaaa", new XpCollectionUpdate(true, "T1", null), null, "ip"));
        a.setState("REVOKED");
        assertThrows(XamPulseException.class, () -> service.updateCollection(account, a.getInstanceId(), new XpCollectionUpdate(true, "T1", null), null, "ip"));
    }

    // ------------------------------------------------------------------ overview

    @Test
    void overviewSummarisesCoverageFromCandidates() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        Instant now = Instant.now();
        when(candidates.candidates(account)).thenReturn(List.of(
                new XpInstanceCandidate("i-1", "a", "ap-south-1", null, "running", Map.of(), true, true, "T1", new XpAgentInfo("1", now, "ACTIVE")),
                new XpInstanceCandidate("i-2", "b", "ap-south-1", null, "running", Map.of(), true, true, "T1", new XpAgentInfo("1", now.minusSeconds(3600), "STALE")),
                new XpInstanceCandidate("i-3", "c", "eu-west-1", null, "running", Map.of(), false, false, "T1", new XpAgentInfo(null, null, "NONE")),
                new XpInstanceCandidate("i-4", "d", "eu-west-1", null, "stopped", Map.of(), null, false, "T1", new XpAgentInfo(null, null, "NONE"))));
        XpSetupOverview o = service.overview(account);
        assertEquals(XpAccountState.PLANNED, o.state());
        assertEquals("ap-south-1", o.homeRegion());
        assertEquals("xamops-xampulse-123456789012-ap-south-1", o.bucket().name());
        assertEquals(4, o.instances().total());
        assertEquals(2, o.instances().ssmManaged());
        assertEquals(2, o.instances().inScope());
        assertEquals(2, o.instances().enrolled());
        assertEquals(1, o.instances().reporting());
        assertEquals(1, o.instances().notCovered());
        assertEquals(33, o.coveragePct());
        assertEquals(now, o.lastDataAt());
        assertEquals(2, o.regions().size());
        assertFalse(o.demo());
    }

    @Test
    void overviewOfAnAccountThatWasNeverSetUp() {
        when(candidates.candidates(account)).thenReturn(List.of());
        XpSetupOverview o = service.overview(account);
        assertEquals(XpAccountState.NOT_SET_UP, o.state());
        assertNull(o.bucket());
        assertEquals(0, o.coveragePct());
    }

    // ------------------------------------------------------------------ stack watcher + checklist

    private XpAccountRegion row(String region, String kind, String state) {
        XpAccountRegion r = new XpAccountRegion();
        r.setCloudAccountId(7L);
        r.setRegion(region);
        r.setKind(kind);
        r.setStackName("GLOBAL".equals(kind) ? "XamOpsXamPulse-" + AWS : "XamOpsXamPulseRegion-" + AWS + "-" + region);
        r.setState(state);
        r.setTargetMode("INSTANCES");
        regionRows.put(region, r);
        return r;
    }

    @Test
    void watcherReadsOutputsRecordsRowStatesAndMovesTheAccountForward() {
        service.plan(account, planCreate(inst("ap-south-1", 1), inst("eu-west-1", 1)), null, "ip");
        StackReader reader = mock(StackReader.class);
        when(reader.describe(account, "ap-south-1", "XamOpsXamPulse-" + AWS)).thenReturn(Optional.of(new StackReader.Stack("CREATE_COMPLETE", null, "sid",
                Map.of("ArchiveRoleArn", "arn:aws:iam::123456789012:role/XamOpsXamPulseArchiveRole", "BucketRegion", "ap-south-1",
                        "KmsKeyArn", "arn:aws:kms:ap-south-1:123456789012:key/k", "TemplateVersion", "1.0.0", "AssociationIds", "abc"), List.of())));
        when(reader.describe(account, "eu-west-1", "XamOpsXamPulseRegion-" + AWS + "-eu-west-1")).thenReturn(Optional.of(new StackReader.Stack("ROLLBACK_COMPLETE",
                "boom", "sid2", Map.of(), List.of("Association CREATE_FAILED: no such instance"))));
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of());
        XamPulseStackWatcher watcher = new XamPulseStackWatcher(configs, regions, agents, mock(CloudAccountRepository.class), reader, mock(SchedulingEnvironmentConfig.class), t -> TelemetryProbe.Landed.unavailable());

        List<XpStackStatus> out = watcher.refresh(account, 7L);

        assertEquals(2, out.size());
        assertEquals("OK", regionRows.get("ap-south-1").getState());
        assertEquals("FAILED", regionRows.get("eu-west-1").getState());
        assertEquals("boom", regionRows.get("eu-west-1").getLastError());
        assertEquals("arn:aws:iam::123456789012:role/XamOpsXamPulseArchiveRole", stored.getArchiveRoleArn());
        assertEquals("arn:aws:kms:ap-south-1:123456789012:key/k", stored.getKmsKeyArn());
        assertEquals("sid", stored.getStackId());
        assertEquals("VERIFYING", stored.getState(), "global stack is healthy; waiting for the first heartbeat");
        assertEquals(List.of("Association CREATE_FAILED: no such instance"), out.stream().filter(s -> s.region().equals("eu-west-1")).findFirst().get().events());
    }

    @Test
    void watcherSurvivesAnAwsErrorInOneRegion() {
        service.plan(account, planCreate(inst("ap-south-1", 1), inst("eu-west-1", 1)), null, "ip");
        StackReader reader = mock(StackReader.class);
        when(reader.describe(any(), any(), any())).thenThrow(new XamPulseException("XP_AWS_UNAVAILABLE", 502, "no aws"));
        XamPulseStackWatcher watcher = new XamPulseStackWatcher(configs, regions, agents, mock(CloudAccountRepository.class), reader, mock(SchedulingEnvironmentConfig.class), t -> TelemetryProbe.Landed.unavailable());
        List<XpStackStatus> out = watcher.refresh(account, 7L);
        assertEquals(2, out.size());
        assertEquals("UNKNOWN", out.get(0).status());
        assertEquals("PLANNED", stored.getState());
    }

    @Test
    void checklistReportsPerRegionWithFixHints() {
        service.plan(account, planCreate(inst("ap-south-1", 2), inst("eu-west-1", 1)), null, "ip");
        regionRows.get("ap-south-1").setState("OK");
        regionRows.get("eu-west-1").setState("FAILED");
        regionRows.get("eu-west-1").setLastError("name clash");
        stored.setArchiveRoleArn("arn:aws:iam::123456789012:role/XamOpsXamPulseArchiveRole");
        stored.setState("VERIFYING");
        XpAgent a = new XpAgent();
        a.setInstanceId(ids(1).get(0));
        a.setRegion("ap-south-1");
        a.setState("ACTIVE");
        a.setLastSeenAt(Instant.now());
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(a));
        ArchiveAccessVerifier verifier = c -> new ArchiveAccessVerifier.Result(true, false, "AccessDenied");
        SetupChecklistService checklist = new SetupChecklistService(configs, regions, agents, mock(XamPulseStackWatcher.class), verifier, t -> TelemetryProbe.Landed.unavailable());

        List<XpChecklistCheck> checks = checklist.snapshot(account);
        Map<String, XpChecklistCheck> by = new HashMap<>();
        checks.forEach(c -> by.put(c.id(), c));

        assertEquals("OK", by.get("global-stack").state());
        assertEquals("OK", by.get("archive-role").state());
        assertEquals("FAIL", by.get("bucket-write").state());
        assertTrue(by.get("bucket-write").hint().contains("bucket policy"), "tells the user the snippet to apply");
        assertEquals("FAIL", by.get("stack:eu-west-1").state());
        assertTrue(by.get("stack:eu-west-1").hint().contains("name clash"));
        assertEquals("1 of 2 selected instances reporting in ap-south-1", by.get("instances:ap-south-1").label());
        assertEquals("WAIT", by.get("instances:ap-south-1").state());
        assertEquals("OK", by.get("agents:ap-south-1").state());
        assertEquals("WAIT", by.get("connected").state());
        assertFalse(SetupChecklistService.isTerminal(checks));
        stored.setState("CONNECTED");
        assertTrue(SetupChecklistService.isTerminal(checklist.snapshot(account)));
        stored.setState("DEGRADED");
        assertEquals("FAIL", checklist.snapshot(account).stream().filter(c -> c.id().equals("connected")).findFirst().get().state());
    }

    @Test
    void checklistShowsFirstMetricsAndFirstLogsWithWaitingHints() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        regionRows.get("ap-south-1").setState("OK");
        XpAgent a = new XpAgent();
        a.setInstanceId(ids(1).get(0));
        a.setRegion("ap-south-1");
        a.setState("ACTIVE");
        a.setLastSeenAt(Instant.now());
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of(a));
        ArchiveAccessVerifier verifier = c -> new ArchiveAccessVerifier.Result(true, true, "ok");
        TelemetryProbe none = t -> new TelemetryProbe.Landed(java.util.Set.of(), java.util.Set.of(), true);
        Map<String, XpChecklistCheck> by = new HashMap<>();
        new SetupChecklistService(configs, regions, agents, mock(XamPulseStackWatcher.class), verifier, none).snapshot(account).forEach(c -> by.put(c.id(), c));
        assertEquals("WAIT", by.get("first-metrics:ap-south-1").state());
        assertTrue(by.get("first-metrics:ap-south-1").hint().startsWith("Waiting for data"));
        assertEquals("WAIT", by.get("first-logs:ap-south-1").state());
        assertTrue(by.get("first-logs:ap-south-1").hint().contains("file permissions"));
        TelemetryProbe both = t -> new TelemetryProbe.Landed(java.util.Set.of("ap-south-1"), java.util.Set.of(ids(1).get(0)), true);
        by.clear();
        new SetupChecklistService(configs, regions, agents, mock(XamPulseStackWatcher.class), verifier, both).snapshot(account).forEach(c -> by.put(c.id(), c));
        assertEquals("OK", by.get("first-metrics:ap-south-1").state());
        assertEquals("OK", by.get("first-logs:ap-south-1").state());
    }

    @Test
    void checklistFailsTheRoleLineWhenTheRoleCannotBeAssumed() {
        service.plan(account, planCreate(inst("ap-south-1", 1)), null, "ip");
        regionRows.get("ap-south-1").setState("OK");
        stored.setArchiveRoleArn("arn:aws:iam::123456789012:role/XamOpsXamPulseArchiveRole");
        when(agents.findByCloudAccountId(7L)).thenReturn(List.of());
        ArchiveAccessVerifier verifier = c -> new ArchiveAccessVerifier.Result(false, false, "Role assumption failed");
        List<XpChecklistCheck> checks = new SetupChecklistService(configs, regions, agents, mock(XamPulseStackWatcher.class), verifier, t -> TelemetryProbe.Landed.unavailable()).snapshot(account);
        assertEquals("FAIL", checks.stream().filter(c -> c.id().equals("archive-role")).findFirst().get().state());
        XpChecklistCheck write = checks.stream().filter(c -> c.id().equals("bucket-write")).findFirst().get();
        assertEquals("WAIT", write.state());
        // The hint names the role ARN and says the ExternalId may differ, never the ExternalId itself.
        String all = checks.toString();
        assertFalse(all.contains(stored.getArchiveExternalIdEnc()));
    }

    @Test
    void checklistBeforeSetupAsksForSetup() {
        when(configs.findByCloudAccountId(7L)).thenReturn(Optional.empty());
        List<XpChecklistCheck> checks = new SetupChecklistService(configs, regions, agents, mock(XamPulseStackWatcher.class), c -> null, t -> TelemetryProbe.Landed.unavailable()).snapshot(account);
        assertEquals(1, checks.size());
        assertEquals("WAIT", checks.get(0).state());
    }
}
