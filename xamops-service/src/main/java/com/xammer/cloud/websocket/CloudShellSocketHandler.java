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

    public CloudShellSocketHandler(AwsConfig awsConfig, CloudAccountRepository cloudAccountRepository) {
        this.awsConfig = awsConfig;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> queryParams = parseQueryParams(session.getUri().getQuery());
        String accountId = queryParams.get("accountId");
        String tenantId = queryParams.get("tenantId");

        if (accountId == null) {
            session.sendMessage(new TextMessage("Error: No accountId provided.\r\n"));
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
                session.sendMessage(new TextMessage("Error: Account not found.\r\n"));
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
                session.sendMessage(new TextMessage("Error: Unsupported provider: " + provider + "\r\n"));
                session.close();
                return;
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            sessions.put(session.getId(), process);

            Thread outputThread = new Thread(() -> streamProcessOutput(process, session));
            outputThread.start();
            outputThreads.put(session.getId(), outputThread);

            String welcomeMsg = String.format(
                    "\r\n\u001B[1;32mWelcome to XamOps Shell!\u001B[0m\r\n" +
                            "Connected to \u001B[1;36m%s\u001B[0m Account: %s\r\n" +
                            "Session timeout: 30 minutes\r\n\r\n",
                    provider, account.getAccountName() != null ? account.getAccountName() : accountId);
            session.sendMessage(new TextMessage(welcomeMsg));

        } catch (Exception e) {
            log.error("Shell setup failed", e);
            session.sendMessage(new TextMessage("Error: " + e.getMessage() + "\r\n"));
            session.close();
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

        return new ProcessBuilder(
                "docker", "run", "--rm", "-i",
                "--memory=512m", "--cpus=0.5",
                "--entrypoint", "script",
                "-e", "AWS_ACCESS_KEY_ID=" + assumeRole.credentials().accessKeyId(),
                "-e", "AWS_SECRET_ACCESS_KEY=" + assumeRole.credentials().secretAccessKey(),
                "-e", "AWS_SESSION_TOKEN=" + assumeRole.credentials().sessionToken(),
                "-e", "AWS_DEFAULT_REGION=us-east-1",
                "-e", "TMOUT=1800", // 30 minute shell timeout
                "shivam777707/xamops-shell:fixed-08-Dec",
                "-qfc", "/bin/bash -i", "/dev/null");
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
                        "export TMOUT=1800 && " + // 30 minute shell timeout
                        "/bin/bash -i",
                projectId, projectId);

        return new ProcessBuilder(
                "docker", "run", "--rm", "-i",
                "--memory=512m", "--cpus=0.5",
                "-e", "GCP_KEY_B64=" + encodedKey,
                "-e", "TMOUT=1800",
                "shivam777707/xamops-shell:fixed-08-Dec",
                "script", "-qfc", bashCommand, "/dev/null");
    }

    private ProcessBuilder setupAzureProcess(String sessionId, CloudAccount account) {
    String clientId = account.getAzureClientId();
    String clientSecret = account.getAzureClientSecret();
    String tenantId = account.getAzureTenantId();
    String subscriptionId = account.getAzureSubscriptionId();

    if (clientId == null || clientSecret == null || tenantId == null) {
        throw new RuntimeException("Azure credentials not configured");
    }

    log.info("Setting up Azure shell for subscription: {}", subscriptionId);

    // Fixed: Use exec to replace the script process with bash, ensuring proper PTY handling
    String bashCommand = String.format(
            "az login --service-principal -u \"$AZ_CLIENT\" -p \"$AZ_SECRET\" --tenant \"$AZ_TENANT\" --output none 2>/dev/null && " +
                    "az account set --subscription %s --output none 2>/dev/null && " +
                    "echo '' && " +
                    "echo '\033[1;32mAuthenticated to Azure Subscription: %s\033[0m' && " +
                    "echo '' && " +
                    "export TMOUT=1800 && " +
                    "export PS1='\\[\\e[1;32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# ' && " +
                    "exec /bin/bash -i",
            subscriptionId, subscriptionId);

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
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                if (session.isOpen()) {
                    // Check for session timeout
                    Long startTime = sessionStartTimes.get(session.getId());
                    if (startTime != null &&
                            (System.currentTimeMillis() - startTime) > SESSION_TIMEOUT_MS) {
                        session.sendMessage(new TextMessage(
                                "\r\n\u001B[1;33mSession timeout reached. Disconnecting...\u001B[0m\r\n"));
                        break;
                    }
                    session.sendMessage(new TextMessage(new String(buffer, 0, read)));
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            // Normal closure
        } catch (Exception e) {
            log.error("Output stream error for session {}", session.getId(), e);
        } finally {
            try {
                if (session.isOpen()) {
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
            OutputStream os = process.getOutputStream();
            os.write(message.getPayload().getBytes());
            os.flush();
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
        TenantContext.clear();
        log.info("WS Closed: Session={}, Status={}", session.getId(), status);
    }
}