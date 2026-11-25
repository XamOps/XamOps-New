package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpLogSource;
import com.xammer.cloud.xampulse.dto.XpBucketCandidate;
import com.xammer.cloud.xampulse.dto.XpLogSourceView;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.repository.XpLogSourceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SourcesAndBucketHealthTest {

    // ------------------------------------------------------------------ bucket picker badges

    private static BucketHealthService.Facts facts(String name, String enc, Boolean ver, Boolean pab, Boolean lc) {
        return new BucketHealthService.Facts(name, "ap-south-1", enc, ver, pab, lc);
    }

    private static List<String> codes(XpBucketCandidate c) {
        return c.issues().stream().map(i -> i.code()).toList();
    }

    @Test
    void aWellConfiguredBucketIsRecommendedWithoutWarnings() {
        XpBucketCandidate c = BucketHealthService.toCandidate(facts("company-logs", "SSE_KMS", true, true, true));
        assertTrue(c.recommended());
        assertTrue(c.issues().isEmpty());
        assertEquals("SSE_KMS", c.encryption());
    }

    @Test
    void weakSettingsBecomeWarningsNeverBlocks() {
        XpBucketCandidate c = BucketHealthService.toCandidate(facts("company-logs", "NONE", false, false, false));
        assertFalse(c.recommended());
        assertEquals(List.of("BUCKET_NOT_ENCRYPTED", "BUCKET_PUBLIC_NOT_BLOCKED", "VERSIONING_OFF", "NO_LIFECYCLE_RULE"), codes(c));
        assertTrue(c.issues().stream().allMatch(i -> "WARN".equals(i.level())), "the customer owns these settings in EXISTING mode");
    }

    @Test
    void unreadableSettingsAreNotReportedAsFailures() {
        XpBucketCandidate c = BucketHealthService.toCandidate(facts("company-logs", "UNKNOWN", null, null, null));
        assertEquals(List.of("ENCRYPTION_UNREADABLE"), codes(c));
        assertNull(c.versioning());
        assertNull(c.publicAccessBlocked());
    }

    @Test
    void costReportBucketsAreFlaggedAndNeverRecommended() {
        XpBucketCandidate c = BucketHealthService.toCandidate(facts("xamops-cur-123456789012", "SSE_KMS", true, true, true));
        assertTrue(codes(c).contains("COST_REPORT_BUCKET"));
        assertFalse(c.recommended());
    }

    @Test
    void ourOwnNamedBucketIsRecommended() {
        assertTrue(BucketHealthService.toCandidate(facts("xamops-xampulse-123456789012-ap-south-1", "SSE_S3", true, true, true)).recommended());
    }

    @Test
    void encryptionIsReadFromTheDefaultRule() {
        assertEquals("SSE_KMS", BucketHealthService.encryption(enc(ServerSideEncryption.AWS_KMS)));
        assertEquals("SSE_S3", BucketHealthService.encryption(enc(ServerSideEncryption.AES256)));
        assertEquals("NONE", BucketHealthService.encryption(null));
    }

    private static GetBucketEncryptionResponse enc(ServerSideEncryption alg) {
        return GetBucketEncryptionResponse.builder().serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
                .rules(ServerSideEncryptionRule.builder().applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder().sseAlgorithm(alg).build()).build())
                .build()).build();
    }

    @Test
    void aLifecycleRuleOnTheXamPulsePrefixIsDetected() {
        LifecycleRule on = LifecycleRule.builder().status(ExpirationStatus.ENABLED).filter(LifecycleRuleFilter.builder().prefix("xamops-xampulse/").build()).build();
        LifecycleRule other = LifecycleRule.builder().status(ExpirationStatus.ENABLED).filter(LifecycleRuleFilter.builder().prefix("tmp/").build()).build();
        assertTrue(BucketHealthService.hasXamPulseRule(List.of(other, on)));
        assertFalse(BucketHealthService.hasXamPulseRule(List.of(other)));
    }

    // ------------------------------------------------------------------ sources

    private static XpLogSourceView src(String kind, List<String> paths, List<XpLogSourceView.RedactionRule> red) {
        return new XpLogSourceView(null, kind, paths, null, true, red);
    }

    @Test
    void validSourcesPass() {
        assertTrue(LogSourceService.validate(List.of(src("SYSTEM", List.of(), List.of()), src("DOCKER", List.of(), List.of()),
                src("CUSTOM", List.of("/var/log/app/*.log"), List.of(new XpLogSourceView.RedactionRule("order-id", "order-\\d{6}"))))).isEmpty());
    }

    @Test
    void badKindsPathsParsersAndRulesAreReported() {
        assertFalse(LogSourceService.validate(List.of(src("KAFKA", List.of(), List.of()))).isEmpty());
        assertFalse(LogSourceService.validate(List.of(src("CUSTOM", List.of(), List.of()))).isEmpty(), "custom needs a path");
        for (String p : new String[] {"relative", "/var/log/../etc/passwd", "/home/u/.ssh/id_rsa", "/var/lib/xampulse/agent-token.json", "/proc/1/environ", "/etc/shadow"}) {
            assertFalse(LogSourceService.validate(List.of(src("CUSTOM", List.of(p), List.of()))).isEmpty(), p);
        }
        assertFalse(LogSourceService.validate(List.of(new XpLogSourceView(null, "CUSTOM", List.of("/a"), "bad parser!", true, List.of()))).isEmpty());
        assertFalse(LogSourceService.validate(List.of(src("SYSTEM", List.of(), List.of(new XpLogSourceView.RedactionRule("x", "(?=a)b"))))).isEmpty());
        assertFalse(LogSourceService.validate(List.of(src("SYSTEM", List.of(), List.of(new XpLogSourceView.RedactionRule("bad name!", "a"))))).isEmpty());
        assertFalse(LogSourceService.validate(null).isEmpty());
    }

    @Test
    void limitsAreEnforced() {
        List<XpLogSourceView> many = java.util.stream.IntStream.range(0, 21).mapToObj(i -> src("SYSTEM", List.of(), List.of())).toList();
        assertFalse(LogSourceService.validate(many).isEmpty());
        List<String> paths = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "/var/log/a" + i).toList();
        assertFalse(LogSourceService.validate(List.of(src("CUSTOM", paths, List.of()))).isEmpty());
    }

    @Test
    void replacingSourcesIsAtomicPerAccountAndMakesAgentsReload() {
        XpLogSourceRepository repo = mock(XpLogSourceRepository.class);
        XpAgentRepository agentRepo = mock(XpAgentRepository.class);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        XpAgent active = agent("ACTIVE", null);
        XpAgent uninstalling = agent("ACTIVE", "UNINSTALL");
        XpAgent revoked = agent("REVOKED", null);
        when(agentRepo.findByCloudAccountId(7L)).thenReturn(List.of(active, uninstalling, revoked));

        List<XpLogSourceView> out = new LogSourceService(repo, agentRepo).replace(7L, List.of(src("SYSTEM", List.of(), List.of()), src("DOCKER", List.of(), List.of())));

        assertEquals(2, out.size());
        verify(repo).deleteByCloudAccountId(7L);
        ArgumentCaptor<XpLogSource> saved = ArgumentCaptor.forClass(XpLogSource.class);
        verify(repo, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(s -> s.getCloudAccountId().equals(7L)), "every row carries the tenant");
        assertEquals("RELOAD_CONFIG", active.getPendingCommand());
        assertEquals(2, active.getConfigVersion());
        assertEquals("UNINSTALL", uninstalling.getPendingCommand(), "a pending uninstall is never replaced");
        assertNull(revoked.getPendingCommand());
        assertEquals(1, revoked.getConfigVersion());
    }

    @Test
    void invalidSourcesChangeNothing() {
        XpLogSourceRepository repo = mock(XpLogSourceRepository.class);
        assertThrows(XamPulseException.class, () -> new LogSourceService(repo, mock(XpAgentRepository.class)).replace(7L, List.of(src("NOPE", List.of(), List.of()))));
        verify(repo, never()).deleteByCloudAccountId(any());
    }

    private static XpAgent agent(String state, String pending) {
        XpAgent a = new XpAgent();
        a.setCloudAccountId(7L);
        a.setState(state);
        a.setPendingCommand(pending);
        a.setConfigVersion(1);
        return a;
    }
}
