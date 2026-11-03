package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.onboarding.PreflightCheck;
import com.xammer.cloud.service.onboarding.OnboardingPreflightService;
import com.xammer.cloud.service.onboarding.PreflightProbe;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-11 (design §6.1): each check's PASS/WARN/FAIL/UNKNOWN, PF-HOP-LIMIT only when identity is NONE,
 * the PF-IDENTITY stored-mode rule (phase 2), and UNKNOWN-blocks (fail closed). Driven by a stub
 * probe; no live AWS call.
 */
class OnboardingPreflightServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);

    private CloudAccount connectedAccount() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setAwsAccountId("111111111111");
        a.setProvider("AWS");
        a.setStatus("CONNECTED");
        return a;
    }

    private KubernetesClusterConfig config() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(100L);
        c.setCloudAccountId(7L);
        c.setClusterName("prod-eks");
        return c;
    }

    private PreflightCheck check(OnboardingPreflightService.Result r, String id) {
        return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    /** A probe that reports a healthy Pod Identity cluster. */
    private PreflightProbe healthyPodIdentity() {
        return new StubProbe() {
            @Override public boolean describeClusterOk(String a, String r, String c) { return true; }
            @Override public String clusterVersion(String a, String r, String c) { return "1.30"; }
            @Override public boolean ec2AndAsgReadOk(String a, String r, String c) { return true; }
            @Override public IdentityResult detectIdentity(String a, String r, String c) { return new IdentityResult("POD_IDENTITY"); }
            @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String r, String c) { return true; }
        };
    }

    @Test
    void healthyPodIdentityCluster_phase1_passes_noHopLimitCheck() {
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(healthyPodIdentity()), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), config(), "prod-eks", "ap-south-1", "READ_ONLY");

        assertThat(check(r, "PF-ACCOUNT").result()).isEqualTo(PreflightCheck.PASS);
        assertThat(check(r, "PF-EKS-READ").result()).isEqualTo(PreflightCheck.PASS);
        assertThat(check(r, "PF-KUBE-VERSION").result()).isEqualTo(PreflightCheck.PASS);
        assertThat(check(r, "PF-IDENTITY").result()).isEqualTo(PreflightCheck.PASS);
        assertThat(r.identityMode()).isEqualTo("POD_IDENTITY");
        assertThat(check(r, "PF-HOP-LIMIT")).isNull(); // only when identity NONE
        assertThat(r.blocking()).isFalse();
    }

    @Test
    void noneIdentity_addsHopLimitCheck_andWarnsInPhase1() {
        PreflightProbe probe = new StubProbe() {
            @Override public boolean describeClusterOk(String a, String r, String c) { return true; }
            @Override public String clusterVersion(String a, String r, String c) { return "1.30"; }
            @Override public IdentityResult detectIdentity(String a, String r, String c) { return new IdentityResult("NONE"); }
            @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String r, String c) { return true; }
            @Override public boolean allInstancesHaveHopLimitAtLeastTwo(String a, String r, String c) { return false; }
        };
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(probe), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), config(), "prod-eks", "ap-south-1", "READ_ONLY");

        assertThat(check(r, "PF-IDENTITY").result()).isEqualTo(PreflightCheck.WARN);
        assertThat(check(r, "PF-HOP-LIMIT")).isNotNull();
        assertThat(check(r, "PF-HOP-LIMIT").result()).isEqualTo(PreflightCheck.WARN);
    }

    @Test
    void noneIdentity_phase2_failsAndBlocks() {
        PreflightProbe probe = new StubProbe() {
            @Override public boolean describeClusterOk(String a, String r, String c) { return true; }
            @Override public String clusterVersion(String a, String r, String c) { return "1.30"; }
            @Override public IdentityResult detectIdentity(String a, String r, String c) { return new IdentityResult("NONE"); }
            @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String r, String c) { return true; }
            @Override public boolean allInstancesHaveHopLimitAtLeastTwo(String a, String r, String c) { return true; }
        };
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(probe), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), config(), "prod-eks", "ap-south-1", "AUTOMATION");
        assertThat(check(r, "PF-IDENTITY").result()).isEqualTo(PreflightCheck.FAIL);
        assertThat(r.blocking()).isTrue();
    }

    @Test
    void phase2_storedModeUnavailable_isFail() {
        KubernetesClusterConfig c = config();
        c.setReadRoleArn("arn:aws:iam::111111111111:role/xamops/read");
        c.setIdentityMode("POD_IDENTITY");
        PreflightProbe probe = new StubProbe() {
            @Override public boolean describeClusterOk(String a, String rg, String cn) { return true; }
            @Override public String clusterVersion(String a, String rg, String cn) { return "1.30"; }
            @Override public IdentityResult detectIdentity(String a, String rg, String cn) { return new IdentityResult("IRSA"); }
            @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String rg, String cn) { return true; }
        };
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(probe), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), c, "prod-eks", "ap-south-1", "AUTOMATION");
        assertThat(check(r, "PF-IDENTITY").result()).isEqualTo(PreflightCheck.FAIL);
        assertThat(check(r, "PF-IDENTITY").detail()).contains("POD_IDENTITY");
    }

    @Test
    void accessDenied_mapsToUnknown_andBlocksWhenOnBlockingCheck() {
        PreflightProbe probe = new StubProbe() {
            @Override public boolean describeClusterOk(String a, String r, String c) {
                throw new PreflightProbe.ProbeUnknownException("AccessDenied", true);
            }
            @Override public String clusterVersion(String a, String r, String c) { return "1.30"; }
            @Override public IdentityResult detectIdentity(String a, String r, String c) { return new IdentityResult("POD_IDENTITY"); }
            @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String r, String c) { return true; }
        };
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(probe), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), config(), "prod-eks", "ap-south-1", "READ_ONLY");
        assertThat(check(r, "PF-EKS-READ").result()).isEqualTo(PreflightCheck.UNKNOWN);
        assertThat(r.blocking()).isTrue(); // UNKNOWN on a blocking check fails closed
    }

    @Test
    void inert_whenNoProbeWired_everyAwsCheckUnknown_noLiveCall() {
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.empty(), clock);
        OnboardingPreflightService.Result r = svc.run(connectedAccount(), config(), "prod-eks", "ap-south-1", "READ_ONLY");
        assertThat(check(r, "PF-EKS-READ").result()).isEqualTo(PreflightCheck.UNKNOWN);
        assertThat(check(r, "PF-KUBE-VERSION").result()).isEqualTo(PreflightCheck.UNKNOWN);
        // PF-ACCOUNT is stored-state only, so it still passes with a connected account.
        assertThat(check(r, "PF-ACCOUNT").result()).isEqualTo(PreflightCheck.PASS);
    }

    @Test
    void disconnectedAccount_pfAccountFails() {
        CloudAccount a = connectedAccount();
        a.setStatus("PENDING");
        OnboardingPreflightService svc = new OnboardingPreflightService(Optional.of(healthyPodIdentity()), clock);
        OnboardingPreflightService.Result r = svc.run(a, config(), "prod-eks", "ap-south-1", "READ_ONLY");
        assertThat(check(r, "PF-ACCOUNT").result()).isEqualTo(PreflightCheck.FAIL);
        assertThat(r.blocking()).isTrue();
    }

    /** Default stub: every probe method reports UNKNOWN unless overridden. */
    private static class StubProbe implements PreflightProbe {
        @Override public boolean describeClusterOk(String a, String r, String c) { throw unk(); }
        @Override public String clusterVersion(String a, String r, String c) { throw unk(); }
        @Override public boolean ec2AndAsgReadOk(String a, String r, String c) { throw unk(); }
        @Override public IdentityResult detectIdentity(String a, String r, String c) { throw unk(); }
        @Override public boolean hasManagedNodegroupsAndNotAutoMode(String a, String r, String c) { throw unk(); }
        @Override public List<NodegroupScaling> describeNodegroups(String a, String r, String c) { throw unk(); }
        @Override public boolean allInstancesHaveHopLimitAtLeastTwo(String a, String r, String c) { throw unk(); }
        private static ProbeUnknownException unk() { return new ProbeUnknownException("stub", false); }
    }
}
