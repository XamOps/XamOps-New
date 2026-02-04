package com.xammer.cloud.websocket;

import com.xammer.cloud.config.AwsConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CloudShellSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CloudShellSocketHandler.class);

    // Connection timeout in milliseconds (30 minutes)
    private static final int SESSION_TIMEOUT_MS = 30 * 60 * 1000;

    private final AwsConfig awsConfig;
    private final CloudAccountRepository cloudAccountRepository;

    private final Map<String, Process> sessions = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputThreads = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();
    // Locks for synchronized message sending per session (prevents TEXT_PARTIAL_WRITING errors)
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public CloudShellSocketHandler(AwsConfig awsConfig, CloudAccountRepository cloudAccountRepository) {
        this.awsConfig = awsConfig;
        this.cloudAccountRepository = cloudAccountRepository;
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
        String tenantId = queryParams.get("tenantId");

        // Initialize session lock for thread-safe message sending
        sessionLocks.put(session.getId(), new Object());

        if (accountId == null) {
            sendMessageSafe(session, "Error: No accountId provided.\r\n");
            session.close();
            return;
        }

        if (tenantId != null && !tenantId.equals("null")) {
            TenantContext.setCurrentTenant(tenantId);
        }

        try {
            log.info("WS Connecting: Session={}, Account={}", session.getId(), accountId);

            // Track session start time
            sessionStartTimes.put(session.getId(), System.currentTimeMillis());

            CloudAccount account = findAccount(accountId);
            if (account == null) {
                sendMessageSafe(session, "Error: Account not found.\r\n");
                session.close();
                return;
            }

            String provider = account.getProvider();
            log.info("Identified Provider: {}", provider);

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

    private CloudAccount findAccount(String id) {
        Optional<CloudAccount> account = cloudAccountRepository.findByProviderAccountId(id);
        if (account.isPresent())
            return account.get();

        List<CloudAccount> awsMatches = cloudAccountRepository.findByAwsAccountId(id);
        if (!awsMatches.isEmpty())
            return awsMatches.get(0);

        Optional<CloudAccount> gcpMatch = cloudAccountRepository.findByGcpProjectId(id);
        if (gcpMatch.isPresent())
            return gcpMatch.get();

        Optional<CloudAccount> azureMatch = cloudAccountRepository.findByAzureSubscriptionId(id);
        return azureMatch.orElse(null);
    }

    private ProcessBuilder setupAwsProcess(String sessionId, CloudAccount account) {
        String externalId = (account.getExternalId() != null && !account.getExternalId().isEmpty())
                ? account.getExternalId()
                : "XamOps_Secure_ID";

        log.info("Assuming AWS Role: {}", account.getRoleArn());

        StsClient sts = awsConfig.stsClient();
        AssumeRoleResponse assumeRole = sts.assumeRole(AssumeRoleRequest.builder()
                .roleArn(account.getRoleArn())
                .roleSessionName("XamOpsShell-" + sessionId)
                .externalId(externalId)
                .durationSeconds(3600) // 1 hour session duration
                .build());

        log.info("AWS Role Assumed Successfully. Credentials obtained.");

        String bashCommand = "export TMOUT=1800 && " +
                "echo '' && " +
                "echo 'Authenticated to AWS Account' && " +
                "echo '' && " +
                "exec /bin/bash -i";

        return new ProcessBuilder(
                "docker", "run", "--rm", "-i",
                "--memory=512m", "--cpus=0.5",
                "-e", "AWS_ACCESS_KEY_ID=" + assumeRole.credentials().accessKeyId(),
                "-e", "AWS_SECRET_ACCESS_KEY=" + assumeRole.credentials().secretAccessKey(),
                "-e", "AWS_SESSION_TOKEN=" + assumeRole.credentials().sessionToken(),
                "-e", "AWS_DEFAULT_REGION=us-east-1",
                "-e", "TMOUT=1800",
                "shivam777707/xamops-shell:fixed-08-Dec",
                "/bin/bash", "-c", bashCommand);
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
        Process process = sessions.get(session.getId());
        if (process != null && process.isAlive()) {
            try {
                OutputStream os = process.getOutputStream();
                os.write(message.getPayload().getBytes());
                os.flush();
                // log.debug("Wrote {} bytes to process for session {}",
                // message.getPayload().length(), session.getId());
            } catch (IOException e) {
                log.error("Failed to write to process for session {}", session.getId(), e);
            }
        } else {
            log.warn("Process not found or not alive for session {}. Cannot write message.", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Process process = sessions.get(session.getId());
        if (process != null) {
            process.destroy();
        }
        sessions.remove(session.getId());
        outputThreads.remove(session.getId());
        sessionStartTimes.remove(session.getId());
        sessionLocks.remove(session.getId()); // Clean up the session lock
        TenantContext.clear();
        log.info("WS Closed: Session={}, Status={}", session.getId(), status);
    }
}