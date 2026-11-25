package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only runbooks over SSM Run Command. Only the fixed scripts below can run (no user text reaches the host, so
 * nothing can be injected); each run is audited, one at a time per host, and its output can be read only by the
 * account that started it. Off unless {@code xampulse.remote-actions.enabled=true}.
 */
@Service("xpRunbookService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class RunbookService {

    private static final Logger log = LoggerFactory.getLogger(RunbookService.class);
    static final int MAX_OUTPUT = 24_000;
    static final Duration LOCK = Duration.ofMinutes(2);
    static final Duration OWNER = Duration.ofHours(1);
    private static final Pattern INSTANCE = Pattern.compile("^i-[0-9a-f]{8,17}$");
    private static final Pattern COMMAND = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CONTROL = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** A fixed, read-only check. {@code script} never contains anything supplied by a user. */
    public record Runbook(String id, String title, String description, String script) {}

    public static final List<Runbook> CATALOG = List.of(
            new Runbook("top", "Top processes", "Processes by CPU, with memory, user and run time.",
                    "ps -eo pid,user,%cpu,%mem,rss,etime,comm --sort=-%cpu | head -n 25"),
            new Runbook("disk", "Disk usage", "Filesystems, then the largest directories under /var.",
                    "df -hT -x tmpfs -x devtmpfs -x squashfs -x overlay; echo; du -xh --max-depth=1 /var 2>/dev/null | sort -rh | head -n 15"),
            new Runbook("sockets", "Listening sockets", "What listens on which port, with the owning process.",
                    "ss -tulpn 2>/dev/null || netstat -tulpn"),
            new Runbook("kernel", "Kernel warnings", "Recent kernel errors and warnings (OOM kills, disk and network errors).",
                    "journalctl -k -p warning -n 60 --no-pager 2>/dev/null || dmesg -T --level=err,warn | tail -n 60"),
            new Runbook("failed", "Failed services", "Failed systemd units and the latest error-level journal lines.",
                    "systemctl --failed --no-pager; echo; journalctl -p err -n 30 --no-pager"),
            new Runbook("oom", "Out-of-memory kills", "OOM kills in the last 24 hours.",
                    "journalctl -k --since '-24h' --no-pager 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -n 30; true"),
            new Runbook("containers", "Containers", "Docker containers with image and status.",
                    "docker ps -a --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}' 2>/dev/null || echo 'Docker is not installed or not running.'"));

    public record Started(String commandId, String runbook) {}

    /** Status: PENDING, IN_PROGRESS, SUCCESS, FAILED, TIMED_OUT, CANCELLED. Output is cleaned and bounded. */
    public record Result(String commandId, String runbook, String status, String output, String error, boolean truncated) {}

    private final XamPulseProperties props;
    private final XamPulseAccessGuard guard;
    private final XpAgentRepository agents;
    private final AwsClientProvider aws;
    private final TicketStore store;
    private final AuditService audit;

    public RunbookService(XamPulseProperties props, XamPulseAccessGuard guard, XpAgentRepository agents, AwsClientProvider aws,
                          TicketStore store, AuditService audit) {
        this.props = props;
        this.guard = guard;
        this.agents = agents;
        this.aws = aws;
        this.store = store;
        this.audit = audit;
    }

    public boolean enabled() {
        return props.getRemoteActions().isEnabled();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw XamPulseException.forbidden("Runbooks are turned off in this deployment.");
        }
    }

    private record Target(CloudAccount account, String region) {}

    private Target target(ClientUserDetails user, Long accountId, String instanceId) {
        if (instanceId == null || !INSTANCE.matcher(instanceId).matches()) {
            throw XamPulseException.notFound();
        }
        CloudAccount account = guard.require(user, accountId);
        if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
            throw XamPulseException.notFound();
        }
        // Only hosts this account enrolled: an instance id from elsewhere is not a target.
        XpAgent agent = agents.findByCloudAccountIdAndInstanceId(accountId, instanceId).orElseThrow(XamPulseException::notFound);
        if (agent.getRegion() == null) {
            throw XamPulseException.notFound();
        }
        return new Target(account, agent.getRegion());
    }

    public Started run(ClientUserDetails user, Long accountId, String instanceId, String runbookId, String ip) {
        requireEnabled();
        Runbook rb = CATALOG.stream().filter(r -> r.id().equals(runbookId)).findFirst().orElseThrow(XamPulseException::notFound);
        Target t = target(user, accountId, instanceId);
        String lock = "xp:ssm:lock:" + accountId + ":" + instanceId;
        if (!store.putIfAbsent(lock, rb.id(), LOCK)) {
            throw XamPulseException.conflict("Another runbook is running on this host. Wait for it to finish.");
        }
        String commandId;
        try {
            SsmClient ssm = aws.getSsmClient(t.account(), t.region());
            commandId = ssm.sendCommand(r -> r.documentName("AWS-RunShellScript").instanceIds(instanceId).timeoutSeconds(60)
                    .comment("XamPulse runbook " + rb.id())
                    .parameters(Map.of("commands", List.of(rb.script()), "executionTimeout", List.of("60")))).command().commandId();
        } catch (SdkException e) {
            store.getAndDelete(lock);
            log.warn("XamPulse runbook {} not sent: {}", rb.id(), e.getClass().getSimpleName());
            throw new XamPulseException("XP_AWS_UNAVAILABLE", 502,
                    "SSM did not accept the command. The connection role needs ssm:SendCommand, and the instance must be managed by SSM.");
        }
        store.put("xp:ssm:cmd:" + commandId, accountId + "|" + instanceId + "|" + rb.id(), OWNER);
        audit.record(user, accountId, "runbook.run", "instance=" + instanceId + ",runbook=" + rb.id() + ",command=" + commandId, ip);
        return new Started(commandId, rb.id());
    }

    public Result result(ClientUserDetails user, Long accountId, String instanceId, String commandId) {
        requireEnabled();
        if (commandId == null || !COMMAND.matcher(commandId).matches()) {
            throw XamPulseException.notFound();
        }
        Target t = target(user, accountId, instanceId);
        Optional<String> owner = store.get("xp:ssm:cmd:" + commandId);
        String[] o = owner.map(s -> s.split("\\|", 3)).orElse(null);
        if (o == null || o.length != 3 || !o[0].equals(String.valueOf(accountId)) || !o[1].equals(instanceId)) {
            throw XamPulseException.notFound(); // not started here, by this account, on this host
        }
        GetCommandInvocationResponse r;
        try {
            r = aws.getSsmClient(t.account(), t.region()).getCommandInvocation(b -> b.commandId(commandId).instanceId(instanceId));
        } catch (InvocationDoesNotExistException e) {
            return new Result(commandId, o[2], "PENDING", "", "", false);
        } catch (SdkException e) {
            throw new XamPulseException("XP_AWS_UNAVAILABLE", 502, "Could not read the command result from SSM.");
        }
        String status = switch (r.statusAsString() == null ? "Pending" : r.statusAsString()) {
            case "Success" -> "SUCCESS";
            case "InProgress", "Delayed" -> "IN_PROGRESS";
            case "TimedOut" -> "TIMED_OUT";
            case "Cancelled", "Cancelling" -> "CANCELLED";
            case "Failed" -> "FAILED";
            default -> "PENDING";
        };
        if (!"PENDING".equals(status) && !"IN_PROGRESS".equals(status)) {
            store.getAndDelete("xp:ssm:lock:" + accountId + ":" + instanceId);
        }
        String out = clean(r.standardOutputContent());
        String err = clean(r.standardErrorContent());
        boolean truncated = out.length() > MAX_OUTPUT;
        return new Result(commandId, o[2], status, truncated ? out.substring(0, MAX_OUTPUT) : out,
                err.length() > 4000 ? err.substring(0, 4000) : err, truncated);
    }

    /** Host output is untrusted text: terminal escape sequences and control characters are removed. */
    static String clean(String s) {
        return s == null ? "" : CONTROL.matcher(s).replaceAll("");
    }
}
