package com.xammer.cloud.websocket;

import com.xammer.cloud.config.AwsConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.Principal;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class CloudShellSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CloudShellSocketHandler.class);

    // Connection timeout in milliseconds (30 minutes)
    private static final int SESSION_TIMEOUT_MS = 30 * 60 * 1000;

    private final AwsConfig awsConfig;
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudAccountAccessService accountAccessService;

    /**
     * Container image the shell runs in. Configurable so the public demo image can be
     * swapped for an image you own, pinned by digest, without a code change.
     */
    @Value("${cloudshell.image:shivam777707/xamops-shell:fixed-08-Dec}")
    private String shellImage;

    /**
     * Default region the shell's AWS CLI targets. The AssumeRole credentials are global,
     * so this only sets AWS_DEFAULT_REGION inside the container; the user can still
     * {@code aws --region ...} anywhere.
     */
    @Value("${cloudshell.aws.default-region:us-east-1}")
    private String defaultAwsRegion;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Process> sessions = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputThreads = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    // Locks for synchronized message sending per session (prevents TEXT_PARTIAL_WRITING errors)
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public CloudShellSocketHandler(AwsConfig awsConfig, CloudAccountRepository cloudAccountRepository,
            CloudAccountAccessService accountAccessService) {
        this.awsConfig = awsConfig;
        this.cloudAccountRepository = cloudAccountRepository;
        this.accountAccessService = accountAccessService;
    }

    /**
     * Pulls the authenticated user off the handshake.
     *
     * <p>
     * Spring Security stores the {@code Authentication} as the WebSocket session principal
     * when the handshake passes through the filter chain. Returns null rather than throwing
     * so the caller can close the socket with a message the terminal UI can display.
     */
    private ClientUserDetails resolveCaller(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof ClientUserDetails details) {
            return details;
        }
        return null;
    }

    /**
     * Thread-safe message sending to prevent TEXT_PARTIAL_WRITING errors.
     * Multiple threads sending to the same WebSocket session must be synchronized.
     */
    private void sendMessageSafe(WebSocketSession session, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        Object lock = sessionLocks.computeIfAbsent(session.getId(), k -> new Object());
        synchronized (lock) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.warn("Failed to send message to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> queryParams = parseQueryParams(session.getUri().getQuery());
        String accountId = queryParams.get("accountId");

        // Initialize session lock for thread-safe message sending
        sessionLocks.put(session.getId(), new Object());

        if (accountId == null) {
            sendMessageSafe(session, "Error: No accountId provided.\r\n");
            session.close();
            return;
        }

        // Who is asking. The handshake is authenticated by the servlet filter chain now
        // that /terminal is no longer permitAll, so a principal must be present; if it is
        // not, something is misconfigured and refusing is the only safe response.
        //
        // Deliberately NOT taken from the query string. This endpoint hands the caller an
        // interactive shell already holding the account's cloud credentials, so identity
        // has to come from the authenticated session, never from a parameter the caller
        // controls.
        ClientUserDetails caller = resolveCaller(session);
        if (caller == null) {
            log.warn("Rejecting CloudShell connection with no authenticated principal (session={})",
                    session.getId());
            sendMessageSafe(session, "Error: Not authenticated.\r\n");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // Tenant is taken from the authenticated user, not from the tenantId query
        // parameter. Honouring a caller-supplied tenant let an attacker point the lookup
        // at another customer's schema, which is how the account resolution below could be
        // steered off-tenant in the first place.
        if (caller.getTenantId() != null && !caller.getTenantId().isBlank()) {
            TenantContext.setCurrentTenant(caller.getTenantId());
        }

        try {
            log.info("WS Connecting: Session={}, User={}", session.getId(), caller.getId());

            // Track session start time
            sessionStartTimes.put(session.getId(), System.currentTimeMillis());

            CloudAccount account = findAccount(accountId);

            // Ownership. findAccount resolves a caller-supplied identifier against the
            // whole table, so without this check naming any account ID was sufficient to
            // be handed a shell in that customer's cloud. Same rule as the HTTP endpoints,
            // via the same service, so the two cannot drift.
            if (account == null || !accountAccessService.canAccess(caller, account)) {
                // One message for both cases: distinguishing "no such account" from "not
                // yours" would confirm which account IDs exist.
                log.warn("Denied CloudShell access: user={} requested an account they do not hold",
                        caller.getId());
                sendMessageSafe(session, "Error: Account not found.\r\n");
                session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }

            String provider = account.getProvider();
            log.info("Identified Provider: {}", provider);

            // Preflight: the shell is a Docker container on this host. If the daemon is
            // unreachable or the image is missing, fail here with a precise message
            // instead of letting `docker run` exit immediately and surfacing a generic
            // "Session ended" in the terminal.
            String preflightError = dockerPreflight();
            if (preflightError != null) {
                log.warn("CloudShell Docker preflight failed: {}", preflightError);
                sendMessageSafe(session,
                        "\r\n\u001B[1;31mCloudShell is unavailable.\u001B[0m\r\n" + preflightError + "\r\n");
                session.close(CloseStatus.SERVICE_RESTARTED);
                return;
            }

            ProcessBuilder pb;
            if ("AWS".equalsIgnoreCase(provider)) {
                pb = setupAwsProcess(session.getId(), account);
            } else if ("GCP".equalsIgnoreCase(provider)) {
                pb = setupGcpProcess(session.getId(), account);
            } else if ("Azure".equalsIgnoreCase(provider)) {
                pb = setupAzureProcess(session.getId(), account);
            } else {
                sendMessageSafe(session, "Error: Unsupported provider: " + provider + "\r\n");
                session.close();
                return;
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            sessions.put(session.getId(), process);

            // Send welcome message BEFORE starting output thread to avoid race condition
            String welcomeMsg = String.format(
                    "\r\n\u001B[1;32mWelcome to XamOps Shell!\u001B[0m\r\n" +
                            "Connected to \u001B[1;36m%s\u001B[0m Account: %s\r\n" +
                            "Session timeout: 30 minutes\r\n\r\n",
                    provider, account.getAccountName() != null ? account.getAccountName() : accountId);
            sendMessageSafe(session, welcomeMsg);

            // Start output thread AFTER welcome message is sent
            Thread outputThread = new Thread(() -> streamProcessOutput(process, session));
            outputThread.start();
            outputThreads.put(session.getId(), outputThread);

        } catch (Throwable e) {
            log.error("Shell setup CRITICAL FAILURE", e);
            try {
                sendMessageSafe(session, "Error: " + e.getMessage() + "\r\n");
                session.close();
            } catch (IOException ex) {
                log.error("Failed to send error message to client", ex);
            }
        }
    }

    /**
     * Verifies the Docker runtime is usable before we try to launch a shell.
     *
     * <p>Two things can make {@code docker run} fail instantly: the daemon is not running
     * (or this process cannot reach its socket), and the shell image is not present and
     * cannot be pulled. Both produced the same late, generic "Session ended" in the UI.
     * Checking here lets us return the actual reason while the socket is still open.
     *
     * @return a human-readable error if the runtime is not ready, or {@code null} if OK.
     */
    private String dockerPreflight() {
        // 1) Is the daemon reachable? `docker info` exits non-zero if it is not.
        try {
            Process info = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            if (!info.waitFor(10, TimeUnit.SECONDS)) {
                info.destroyForcibly();
                return "The Docker daemon did not respond within 10s on the backend host.";
            }
            if (info.exitValue() != 0) {
                return "The Docker daemon is not reachable on the backend host. "
                        + "Ensure Docker is installed and running, and that this service can reach the Docker socket.";
            }
        } catch (IOException e) {
            return "Docker is not installed on the backend host (could not execute `docker`).";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while checking the Docker daemon.";
        }

        // 2) Is the shell image available? Check locally first, then try a pull so a fresh
        //    host self-heals on first use instead of failing.
        try {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", shellImage)
                    .redirectErrorStream(true)
                    .start();
            boolean done = inspect.waitFor(10, TimeUnit.SECONDS);
            if (done && inspect.exitValue() == 0) {
                return null; // image present
            }
            if (!done) {
                inspect.destroyForcibly();
            }

            log.info("Shell image {} not present locally; attempting pull", shellImage);
            Process pull = new ProcessBuilder("docker", "pull", shellImage)
                    .redirectErrorStream(true)
                    .start();
            if (!pull.waitFor(120, TimeUnit.SECONDS)) {
                pull.destroyForcibly();
                return "The shell image '" + shellImage + "' is not present and the pull timed out.";
            }
            if (pull.exitValue() != 0) {
                return "The shell image '" + shellImage + "' could not be pulled. "
                        + "Check the image name and registry access on the backend host.";
            }
        } catch (IOException e) {
            return "Could not verify the shell image '" + shellImage + "': " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while verifying the shell image.";
        }
        return null;
    }

    private CloudAccount findAccount(String id) {
        // Try provider account ID first
        Optional<CloudAccount> account = cloudAccountRepository.findByProviderAccountId(id);
        if (account.isPresent())
            return account.get();

        // Try AWS account ID
        List<CloudAccount> awsMatches = cloudAccountRepository.findByAwsAccountId(id);
        if (!awsMatches.isEmpty())
            return awsMatches.get(0);

        // Try GCP project ID - handle potential duplicates gracefully
        try {
            Optional<CloudAccount> gcpMatch = cloudAccountRepository.findByGcpProjectId(id);
            if (gcpMatch.isPresent())
                return gcpMatch.get();
        } catch (Exception e) {
            // Handle NonUniqueResultException - if duplicates exist, log warning and try to find first match
            log.warn("Multiple GCP accounts found for project ID '{}'. Using first match. Please remove duplicates.", id);
            List<CloudAccount> gcpAccounts = cloudAccountRepository.findAll().stream()
                    .filter(acc -> id.equals(acc.getGcpProjectId()))
                    .collect(java.util.stream.Collectors.toList());
            if (!gcpAccounts.isEmpty()) {
                return gcpAccounts.get(0);
            }
        }

        // Try Azure subscription ID - handle potential duplicates gracefully
        try {
            Optional<CloudAccount> azureMatch = cloudAccountRepository.findByAzureSubscriptionId(id);
            return azureMatch.orElse(null);
        } catch (Exception e) {
            log.warn("Multiple Azure accounts found for subscription ID '{}'. Using first match. Please remove duplicates.", id);
            List<CloudAccount> azureAccounts = cloudAccountRepository.findAll().stream()
                    .filter(acc -> id.equals(acc.getAzureSubscriptionId()))
                    .collect(java.util.stream.Collectors.toList());
            if (!azureAccounts.isEmpty()) {
                return azureAccounts.get(0);
            }
        }

        return null;
    }

    private ProcessBuilder setupAwsProcess(String sessionId, CloudAccount account) {
        // An ExternalId is the confused-deputy defence on AssumeRole: it is the shared
        // secret proving the request came from XamOps rather than from someone else who
        // learned the customer's role ARN. Falling back to a constant compiled into the
        // source defeated that entirely — the value was the same for every account that had
        // none configured, and it is in the repository.
        //
        // Failing closed instead. An account with no ExternalId is a misconfigured
        // onboarding, and refusing the shell is better than assuming the role with a
        // publicly-known value.
        String externalId = account.getExternalId();
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalStateException(
                    "No ExternalId configured for this account. Re-run onboarding to set one; "
                            + "CloudShell will not assume a role without it.");
        }

        log.info("Assuming AWS Role: {}", account.getRoleArn());

        StsClient sts = awsConfig.stsClient();
        AssumeRoleResponse assumeRole = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn(account.getRoleArn())
                .roleSessionName("XamOpsShell-" + sessionId)
                .externalId(externalId)
                .durationSeconds(3600) // 1 hour session duration
                .build());

        log.info("AWS Role Assumed Successfully. Credentials obtained.");

        return new ProcessBuilder(
                buildDockerRunCommand(
                        containerName(sessionId),
                        List.of(
                                "-e", "AWS_ACCESS_KEY_ID=" + assumeRole.credentials().accessKeyId(),
                                "-e", "AWS_SECRET_ACCESS_KEY=" + assumeRole.credentials().secretAccessKey(),
                                "-e", "AWS_SESSION_TOKEN=" + assumeRole.credentials().sessionToken(),
                                "-e", "AWS_DEFAULT_REGION=" + defaultAwsRegion,
                                "-e", "AWS_REGION=" + defaultAwsRegion),
                        "echo 'Authenticated to AWS Account' && "
                                + "echo 'Tip: aws ssm start-session --target <instance-id> to open a shell on any SSM-managed EC2.'"));
    }

    /**
     * Builds the {@code docker run} argument list shared by all providers.
     *
     * <p>Two things matter here and both were the cause of earlier failures:
     *
     * <ul>
     * <li><b>No {@code -t}.</b> We drive the container over Java pipes
     * ({@code process.getInputStream()/getOutputStream()}), so the backend's stdin is not
     * a terminal. {@code docker run -t} demands a real TTY on stdin and the container dies
     * immediately with "the input device is not a TTY". So we use {@code -i} only.
     * <li><b>PTY inside the container via {@code script}.</b> Interactive tools (vim, top,
     * less) and especially {@code aws ssm start-session} — the supported way to reach any
     * EC2 with no SSH key or public IP — require a controlling terminal. {@code script -q
     * /dev/null -c "bash -i"} allocates a pseudo-TTY in software for the shell without
     * needing Docker to allocate one on the (piped) host stdin. {@code stty} resize
     * messages then apply to this PTY.
     * </ul>
     *
     * @param containerName deterministic name so resize/cleanup can target the container
     * @param envArgs       provider credential {@code -e KEY=VALUE} pairs
     * @param authEchoChunk provider-specific banner commands run before the shell starts
     */
    private List<String> buildDockerRunCommand(String containerName, List<String> envArgs, String authEchoChunk) {
        // The inner command sets the idle timeout, prints the banner, then hands control to
        // an interactive bash running under a software PTY. Single-quoted for `script -c`.
        String inner = "export TMOUT=1800; "
                + "echo ''; "
                + authEchoChunk + "; "
                + "echo ''; "
                + "exec /bin/bash -i";
        String scriptCommand = "export TMOUT=1800 && "
                + "exec script -q /dev/null -c " + shellQuote(inner);

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-i",
                "--name", containerName,
                "--memory=512m", "--cpus=0.5",
                "-e", "TERM=xterm-256color",
                "-e", "TMOUT=1800"));
        cmd.addAll(envArgs);
        cmd.add(shellImage);
        cmd.add("/bin/bash");
        cmd.add("-c");
        cmd.add(scriptCommand);
        return cmd;
    }

    /**
     * Wraps a string in single quotes for safe use as one shell word, escaping any
     * embedded single quotes. Credentials never flow through here (they are passed as
     * separate {@code -e} ProcessBuilder args, not interpolated into a command line).
     */
    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Deterministic, DNS-safe container name derived from the WebSocket session id, so a
     * resize message can target this session's container with {@code docker exec}.
     */
    private String containerName(String sessionId) {
        return "xamops-shell-" + sessionId.replaceAll("[^a-zA-Z0-9_.-]", "");
    }

    private ProcessBuilder setupGcpProcess(String sessionId, CloudAccount account) {
        String serviceAccountKey = account.getGcpServiceAccountKey();
        String projectId = account.getGcpProjectId();

        if (serviceAccountKey == null || serviceAccountKey.isEmpty()) {
            throw new RuntimeException("GCP service account key not configured");
        }

        log.info("Setting up GCP shell for project: {}", projectId);
        String encodedKey = Base64.getEncoder().encodeToString(serviceAccountKey.getBytes());

        String bashCommand = String.format(
                "echo \"$GCP_KEY_B64\" | base64 -d > /tmp/gcp_key.json && " +
                        "gcloud auth activate-service-account --key-file=/tmp/gcp_key.json --quiet && " +
                        "gcloud config set project %s --quiet && " +
                        "rm -f /tmp/gcp_key.json && " +
                        "echo '' && " +
                        "echo 'Authenticated to GCP Project: %s' && " +
                        "echo '' && " +
                        "export TMOUT=1800 && " +
                        "exec /bin/bash -i",
                projectId, projectId);

        return new ProcessBuilder(
                "docker", "run", "--rm", "-i",
                "--memory=512m", "--cpus=0.5",
                "-e", "GCP_KEY_B64=" + encodedKey,
                "-e", "TMOUT=1800",
                "shivam777707/xamops-shell:fixed-08-Dec",
                "/bin/bash", "-c", bashCommand);
    }

    private ProcessBuilder setupAzureProcess(String sessionId, CloudAccount account) {
        String clientId = account.getAzureClientId();
        String clientSecret = account.getAzureClientSecret();
        String tenantId = account.getAzureTenantId();
        String subscriptionId = account.getAzureSubscriptionId();

        if (clientId == null || clientSecret == null || tenantId == null) {
            throw new RuntimeException("Azure credentials not configured. Missing: " +
                    (clientId == null ? "clientId " : "") +
                    (clientSecret == null ? "clientSecret " : "") +
                    (tenantId == null ? "tenantId" : ""));
        }

        if (subscriptionId == null || subscriptionId.isEmpty()) {
            throw new RuntimeException("Azure subscription ID not configured");
        }

        log.info("Setting up Azure shell for subscription: {}", subscriptionId);
        log.debug("Azure credentials - ClientId: {}, TenantId: {}", clientId, tenantId);

        // Improved: Show errors instead of suppressing them, better error handling
        String bashCommand = String.format(
                "echo 'Authenticating to Azure...' && " +
                        "az login --service-principal -u \"$AZ_CLIENT\" -p \"$AZ_SECRET\" --tenant \"$AZ_TENANT\" --output none || " +
                        "{ echo '\033[1;31mAzure authentication failed. Check credentials.\033[0m'; exit 1; } && " +
                        "az account set --subscription %s --output none || " +
                        "{ echo '\033[1;31mFailed to set subscription: %s\033[0m'; exit 1; } && " +
                        "echo '' && " +
                        "echo '\033[1;32mAuthenticated to Azure Subscription: %s\033[0m' && " +
                        "echo '' && " +
                        "export TMOUT=1800 && " +
                        "export PS1='\\[\\e[1;32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# ' && " +
                        "exec /bin/bash -i",
                subscriptionId, subscriptionId, subscriptionId);

        return new ProcessBuilder(
                "docker", "run", "--rm", "-i",
                "--memory=512m", "--cpus=0.5",
                "-e", "AZ_CLIENT=" + clientId,
                "-e", "AZ_SECRET=" + clientSecret,
                "-e", "AZ_TENANT=" + tenantId,
                "-e", "TMOUT=1800",
                "shivam777707/xamops-shell:fixed-08-Dec",
                "/bin/bash", "-c", bashCommand);
    }

    private void streamProcessOutput(Process process, WebSocketSession session) {
        log.info("Starting output stream thread for session {}", session.getId());
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                String data = new String(buffer, 0, read);
                log.debug("Read from Docker process ({} bytes)", read); // Changed to debug level

                if (session.isOpen()) {
                    // Check for session timeout
                    Long startTime = sessionStartTimes.get(session.getId());
                    if (startTime != null &&
                            (System.currentTimeMillis() - startTime) > SESSION_TIMEOUT_MS) {
                        sendMessageSafe(session,
                                "\r\n\u001B[1;33mSession timeout reached. Disconnecting...\u001B[0m\r\n");
                        break;
                    }
                    // Use synchronized sending to prevent TEXT_PARTIAL_WRITING errors
                    sendMessageSafe(session, data);
                } else {
                    log.warn("Session closed during read loop: {}", session.getId());
                    break;
                }
            }
            log.info("Process output stream finished (read returned -1).");

            // Check exit code
            if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.info("Process exited with code: {}", process.exitValue());
            } else {
                log.warn("Process is still alive after stream close?");
            }

        } catch (IOException e) {
            log.error("IOException in streamProcessOutput for session {}", session.getId(), e);
        } catch (Exception e) {
            log.error("Output stream error for session {}", session.getId(), e);
        } finally {
            try {
                if (session.isOpen()) {
                    log.info("Closing session from finally block: {}", session.getId());
                    session.close();
                }
            } catch (IOException e) {
                log.error("Error closing session {}", session.getId(), e);
            }
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    map.put(pair[0], pair[1]);
                }
            }
        }
        return map;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        // Resize control frames arrive as JSON ({"type":"resize","cols":..,"rows":..}).
        // The PTY inside the container must be told the new size or output wraps wrong and
        // full-screen tools (vim, aws ssm start-session) render incorrectly. Keystrokes are
        // raw text and are forwarded to the process stdin unchanged.
        if (payload.startsWith("{") && payload.contains("\"type\"")) {
            if (handleControlMessage(session, payload)) {
                return;
            }
        }

        Process process = sessions.get(session.getId());
        if (process != null && process.isAlive()) {
            try {
                OutputStream os = process.getOutputStream();
                os.write(payload.getBytes());
                os.flush();
            } catch (IOException e) {
                log.error("Failed to write to process for session {}", session.getId(), e);
            }
        } else {
            log.warn("Process not found or not alive for session {}. Cannot write message.", session.getId());
        }
    }

    /**
     * Applies a resize control frame to the session's container PTY.
     *
     * @return true if the payload was a recognised control frame (and must not be
     *         forwarded to stdin), false if it should be treated as ordinary input.
     */
    private boolean handleControlMessage(WebSocketSession session, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!"resize".equals(node.path("type").asText())) {
                return false;
            }
            int cols = node.path("cols").asInt(0);
            int rows = node.path("rows").asInt(0);
            // Sanity-bound the values; a caller-supplied size goes into a command line.
            if (cols < 1 || cols > 1000 || rows < 1 || rows > 1000) {
                return true; // recognised but ignored
            }
            String container = containerName(session.getId());
            // `stty` on the container's controlling TTY updates the window size, which the
            // shell and child processes pick up via SIGWINCH.
            new ProcessBuilder("docker", "exec", container,
                    "stty", "cols", Integer.toString(cols), "rows", Integer.toString(rows))
                    .redirectErrorStream(true)
                    .start();
            return true;
        } catch (IOException e) {
            // Not valid JSON, or exec failed — treat as ordinary input rather than losing it.
            log.debug("Resize control frame handling failed for session {}: {}",
                    session.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Process process = sessions.get(session.getId());
        if (process != null) {
            process.destroy();
        }
        // Named containers run with --rm, so a clean exit removes them. If the client
        // vanished without the process noticing (half-open socket), the container can
        // linger and block a reconnect that reuses the name — force-remove to be safe.
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName(session.getId()))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            log.debug("Best-effort container cleanup failed for session {}: {}",
                    session.getId(), e.getMessage());
        }
        sessions.remove(session.getId());
        outputThreads.remove(session.getId());
        sessionStartTimes.remove(session.getId());
        sessionLocks.remove(session.getId()); // Clean up the session lock
        TenantContext.clear();
        log.info("WS Closed: Session={}, Status={}", session.getId(), status);
    }
}