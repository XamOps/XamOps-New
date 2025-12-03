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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CloudShellSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CloudShellSocketHandler.class);

    private final AwsConfig awsConfig;
    private final CloudAccountRepository cloudAccountRepository;

    private final Map<String, Process> sessions = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputThreads = new ConcurrentHashMap<>();

    public CloudShellSocketHandler(AwsConfig awsConfig, CloudAccountRepository cloudAccountRepository) {
        this.awsConfig = awsConfig;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String query = session.getUri().getQuery();
        
        Map<String, String> queryParams = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    queryParams.put(pair[0], pair[1]);
                }
            }
        }

        String accountId = queryParams.get("accountId");
        String tenantId = queryParams.get("tenantId");

        if (accountId == null) {
            session.sendMessage(new TextMessage("Error: No accountId provided in URL"));
            session.close();
            return;
        }

        if (tenantId != null && !tenantId.isEmpty() && !tenantId.equals("null")) {
            TenantContext.setCurrentTenant(tenantId);
        } else {
            log.warn("No tenantId found in WebSocket connection. Defaulting to master DB.");
        }

        try {
            log.info("WS Connected: Session ID={}, Account={}, Tenant={}", session.getId(), accountId, tenantId);

            List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);

            if (accounts.isEmpty()) {
                throw new RuntimeException("Account " + accountId + " not found in database.");
            }

            CloudAccount account = accounts.get(0);
            String targetRoleArn = account.getRoleArn();
            
            // FIX: Fetch External ID from DB, fallback to default if missing
            String externalId = account.getExternalId(); 
            if (externalId == null || externalId.isEmpty()) {
                 externalId = "XamOps_Secure_ID"; // Fallback only if DB is empty
            }

            log.info("Assuming Role: {} with ExternalID: {}", targetRoleArn, externalId);

            StsClient sts = awsConfig.stsClient();
            AssumeRoleResponse assumeRole = sts.assumeRole(AssumeRoleRequest.builder()
                    .roleArn(targetRoleArn)
                    .roleSessionName("XamOpsShell-" + session.getId())
                    .externalId(externalId) // <--- UPDATED HERE
                    .build());

            String accessKey = assumeRole.credentials().accessKeyId();
            String secretKey = assumeRole.credentials().secretAccessKey();
            String sessionToken = assumeRole.credentials().sessionToken();

            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "--rm",
                    "-i",
                    "--entrypoint", "script",
                    "-e", "AWS_ACCESS_KEY_ID=" + accessKey,
                    "-e", "AWS_SECRET_ACCESS_KEY=" + secretKey,
                    "-e", "AWS_SESSION_TOKEN=" + sessionToken,
                    "-e", "AWS_DEFAULT_REGION=us-east-1",
                    "shivam777707/xamops-shell:latest",
                    "xamops-shell",
                    "-qfc", "/bin/bash -i", "/dev/null");

            pb.redirectErrorStream(true);
            Process process = pb.start();
            sessions.put(session.getId(), process);

            log.info("Docker process started: PID={}", process.pid());

            Thread outputThread = new Thread(() -> {
                try (InputStream is = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        session.sendMessage(new TextMessage(new String(buffer, 0, read)));
                    }
                } catch (IOException e) {
                    // Stream closed
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            outputThread.start();
            outputThreads.put(session.getId(), outputThread);

            session.sendMessage(new TextMessage("Connected to Account: " + accountId + "\r\n"));

        } catch (Exception e) {
            log.error("Shell setup failed: {}", e.getMessage());
            session.sendMessage(new TextMessage("Error connecting: " + e.getMessage()));
            session.close();
        }
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
        TenantContext.clear();
    }
}