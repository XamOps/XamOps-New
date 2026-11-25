package com.xammer.cloud.xampulse.service.setup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.dto.XpChecklistCheck;
import com.xammer.cloud.xampulse.dto.XpStackStatus;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The live checklist of plan 3.1 step 7, reported per region, each line OK / WAIT / FAIL with a one-line fix hint
 * from the troubleshooting table. {@link #verify} refreshes from AWS; {@link #snapshot} uses what the watcher last
 * stored (cheap enough for the 3 s stream).
 */
@Service("xpSetupChecklistService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class SetupChecklistService {

    static final String DOC = "https://docs.aws.amazon.com/systems-manager/latest/userguide/ssm-agent.html";

    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final XamPulseStackWatcher watcher;
    private final ArchiveAccessVerifier archive;
    private final TelemetryProbe probe;
    private final Clock clock;
    private final Cache<Long, ArchiveAccessVerifier.Result> archiveCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1000).build();

    @org.springframework.beans.factory.annotation.Autowired
    public SetupChecklistService(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                                 XamPulseStackWatcher watcher, ArchiveAccessVerifier archive, TelemetryProbe probe) {
        this(configs, regions, agents, watcher, archive, probe, Clock.systemUTC());
    }

    SetupChecklistService(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                          XamPulseStackWatcher watcher, ArchiveAccessVerifier archive, TelemetryProbe probe, Clock clock) {
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
        this.watcher = watcher;
        this.archive = archive;
        this.probe = probe;
        this.clock = clock;
    }

    /** Forces a fresh AWS read and a fresh archive write test, then evaluates. */
    public List<XpChecklistCheck> verify(CloudAccount account) {
        List<XpStackStatus> statuses = watcher.refresh(account, account.getId());
        archiveCache.invalidate(account.getId());
        return evaluate(account.getId(), statuses);
    }

    /** From stored state only; no AWS stack call. The archive write test is cached for a minute. */
    public List<XpChecklistCheck> snapshot(CloudAccount account) {
        return evaluate(account.getId(), null);
    }

    List<XpChecklistCheck> evaluate(Long accountId, List<XpStackStatus> live) {
        List<XpChecklistCheck> out = new ArrayList<>();
        Optional<XpAccountConfig> cfgOpt = configs.findByCloudAccountId(accountId);
        if (cfgOpt.isEmpty()) {
            out.add(new XpChecklistCheck("setup", null, "XamPulse is not set up for this account", "WAIT",
                    "Choose a bucket and instances, then deploy.", null));
            return out;
        }
        XpAccountConfig cfg = cfgOpt.get();
        List<XpAccountRegion> rows = regions.findByCloudAccountId(accountId);
        List<XpAgent> agentRows = agents.findByCloudAccountId(accountId);
        Instant now = clock.instant();

        XpAccountRegion global = rows.stream().filter(r -> "GLOBAL".equals(r.getKind())).findFirst().orElse(null);
        out.add(stackCheck("global-stack", cfg.getHomeRegion(), "Global stack created in " + cfg.getHomeRegion(), global));

        boolean globalOk = global != null && "OK".equals(global.getState());
        if (globalOk) {
            ArchiveAccessVerifier.Result r = archiveCache.get(accountId, k -> archive.verify(cfg));
            out.add(new XpChecklistCheck("archive-role", null, "Archive role can be assumed", r.assumable() ? "OK" : "FAIL",
                    r.assumable() ? null : "The ExternalId does not match or the stack is still finishing. The role ARN is "
                            + nullToDash(cfg.getArchiveRoleArn()) + "; re-run the check in a minute.", null));
            if (r.assumable()) {
                out.add(new XpChecklistCheck("bucket-write", null, "Test object written to " + cfg.getBucketName(),
                        r.writable() ? "OK" : "FAIL",
                        r.writable() ? null : "Write refused (" + r.detail() + "). For an existing bucket apply the bucket policy and KMS snippets from the preview.", null));
            } else {
                out.add(new XpChecklistCheck("bucket-write", null, "Test object written to " + cfg.getBucketName(), "WAIT",
                        "Waiting for the archive role.", null));
            }
        } else {
            out.add(new XpChecklistCheck("archive-role", null, "Archive role can be assumed", "WAIT", "Waiting for the global stack.", null));
            out.add(new XpChecklistCheck("bucket-write", null, "Test object written to " + nullToDash(cfg.getBucketName()), "WAIT",
                    "Waiting for the global stack.", null));
        }

        boolean anyReporting = agentRows.stream().anyMatch(a -> TelemetryRules.reporting(a, now));
        TelemetryProbe.Landed landed = anyReporting ? probe.landed(accountId) : new TelemetryProbe.Landed(Set.of(), Set.of(), true);
        for (XpAccountRegion row : rows) {
            String region = row.getRegion();
            if (!"GLOBAL".equals(row.getKind())) {
                out.add(stackCheck("stack:" + region, region, "Regional stack created in " + region, row));
            }
            out.add(associationCheck(region, row, live));
            List<XpAgent> inRegion = agentRows.stream().filter(a -> region.equals(a.getRegion())).toList();
            long reporting = inRegion.stream().filter(a -> isRecent(a, now)).count();
            long enrolled = inRegion.stream().filter(a -> "ACTIVE".equals(a.getState())).count();
            Set<String> targets = InstanceCandidateService.targetIds(row);
            long wanted = "INSTANCES".equals(row.getTargetMode()) ? targets.size() : Math.max(enrolled, 0);
            String label = "INSTANCES".equals(row.getTargetMode())
                    ? reporting + " of " + wanted + " selected instances reporting in " + region
                    : reporting + " instances reporting in " + region;
            boolean done = "INSTANCES".equals(row.getTargetMode()) ? wanted > 0 && reporting >= wanted : reporting > 0;
            out.add(new XpChecklistCheck("instances:" + region, region, label, done ? "OK" : "WAIT",
                    done ? null : "Instances need SSM Agent, an instance profile with AmazonSSMManagedInstanceCore and a network path to Systems Manager. "
                            + "The association runs every 30 minutes; you can also run the one-line installer.", DOC));
            out.add(new XpChecklistCheck("agents:" + region, region, enrolled + " agent(s) enrolled in " + region, enrolled > 0 ? "OK" : "WAIT",
                    enrolled > 0 ? null : "Agents enrol on their first run. If instances report in SSM but never enrol, check outbound HTTPS to XamOps and the IMDS hop limit.", null));
            boolean regionReporting = inRegion.stream().anyMatch(a -> TelemetryRules.reporting(a, now));
            out.add(dataCheck("first-metrics:" + region, region, "First metrics received from " + region, regionReporting, landed,
                    TelemetryRules.metricsLanded(region, inRegion, landed),
                    "Waiting for data: the agent is running but no metrics have arrived yet. This usually takes 1 to 2 minutes. If it persists, check that the instance can reach the XamOps gateway over HTTPS (port 443) and that the collector is running (systemctl status xampulse-agent)."));
            out.add(dataCheck("first-logs:" + region, region, "First logs received from " + region, regionReporting, landed,
                    TelemetryRules.logsLanded(region, landed),
                    "Waiting for data: no log lines have arrived yet. Logs only ship when a source is enabled and the files are readable and being written to. Check the log sources you selected, file permissions (the agent user must read them) and that the application is writing logs."));
        }

        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        out.add(new XpChecklistCheck("connected", null, "Connected", state == XpAccountState.CONNECTED ? "OK"
                : state == XpAccountState.DEGRADED ? "FAIL" : "WAIT",
                state == XpAccountState.DEGRADED ? "The global stack is unhealthy or no agent has reported for 15 minutes. Check the failing lines above."
                : state == XpAccountState.VERIFYING ? "Connected is reached when an agent reports and metrics or logs have arrived from every reporting region. See the waiting lines above." : null, null));
        return out;
    }

    private static XpChecklistCheck dataCheck(String id, String region, String label, boolean regionReporting,
                                              TelemetryProbe.Landed landed, boolean ok, String hint) {
        if (ok) {
            return new XpChecklistCheck(id, region, label, "OK", null, null);
        }
        if (!regionReporting) {
            return new XpChecklistCheck(id, region, label, "WAIT", "Waiting for an agent in this region to report.", null);
        }
        if (!landed.available()) {
            return new XpChecklistCheck(id, region, label, "WAIT", "The telemetry store could not be read just now; this line will update on the next check.", null);
        }
        return new XpChecklistCheck(id, region, label, "WAIT", hint, null);
    }

    private static XpChecklistCheck stackCheck(String id, String region, String label, XpAccountRegion row) {
        if (row == null) {
            return new XpChecklistCheck(id, region, label, "WAIT", "Deploy the stack from the setup screen.", null);
        }
        return switch (String.valueOf(row.getState())) {
            case "OK" -> new XpChecklistCheck(id, region, label, "OK", null, null);
            case "FAILED" -> new XpChecklistCheck(id, region, label, "FAIL",
                    "The stack did not finish" + (row.getLastError() == null ? "" : " (" + row.getLastError() + ")")
                            + ". Open the stack events in " + region + ", fix the cause and deploy again.", null);
            case "DEPLOYING" -> new XpChecklistCheck(id, region, label, "WAIT", "CloudFormation is still creating resources.", null);
            default -> new XpChecklistCheck(id, region, label, "WAIT", "Deploy the stack from the setup screen.", null);
        };
    }

    private static XpChecklistCheck associationCheck(String region, XpAccountRegion row, List<XpStackStatus> live) {
        String label = "Install association created in " + region;
        if (!"OK".equals(row.getState())) {
            return new XpChecklistCheck("association:" + region, region, label, "WAIT", "Waiting for the stack.", null);
        }
        if (live != null) {
            for (XpStackStatus s : live) {
                if (region.equals(s.region()) && s.outputs() != null) {
                    String ids = s.outputs().get("AssociationIds");
                    if (ids == null || ids.replace(",", "").isBlank()) {
                        return new XpChecklistCheck("association:" + region, region, label, "FAIL",
                                "The stack has no association. Check the selected instances or tag and update the stack.", null);
                    }
                    return new XpChecklistCheck("association:" + region, region, label, "OK", null, null);
                }
            }
        }
        // Not re-read from AWS in a snapshot: a healthy stack is enough until the next verify.
        return new XpChecklistCheck("association:" + region, region, label, "OK", null, null);
    }

    static boolean isRecent(XpAgent a, Instant now) {
        return "ACTIVE".equals(a.getState()) && a.getLastSeenAt() != null
                && Duration.between(a.getLastSeenAt(), now).compareTo(InstanceCandidateService.STALE_AFTER) <= 0;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "(not known yet)" : s;
    }

    /** True once the account is connected or has failed for good, so the stream can end early. */
    public static boolean isTerminal(List<XpChecklistCheck> checks) {
        return checks.stream().anyMatch(c -> "connected".equals(c.id()) && "OK".equals(c.state()));
    }
}
