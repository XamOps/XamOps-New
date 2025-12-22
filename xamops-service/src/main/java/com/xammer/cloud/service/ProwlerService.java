package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ProwlerService {

    private static final Logger logger = LoggerFactory.getLogger(ProwlerService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisCacheService redisCacheService;
    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;

    @Value("${prowler.executable.path:}")
    private String configuredProwlerPath;

    @Autowired
    public ProwlerService(RedisCacheService redisCacheService,
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider) {
        this.redisCacheService = redisCacheService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
    }

    @Async("prowlerTaskExecutor")
    public void triggerScanAsync(String accountId, String region, String... serviceGroups) {
        String statusKey = "prowler_status_" + accountId;
        String findingsKey = "prowler_findings_" + accountId;
        String securityServiceCacheKey = "securityFindings-" + accountId;

        try {
            // 1. Update Status to RUNNING
            Map<String, Object> status = new HashMap<>();
            status.put("status", "RUNNING");
            status.put("startTime", LocalDateTime.now().toString());
            status.put("message", "Initializing Prowler scan for ALL regions...");
            redisCacheService.put(statusKey, status, 60);

            logger.info("Async Prowler scan started for account {}", accountId);

            // 2. Fetch Account and Credentials
            List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
            if (accounts.isEmpty()) {
                throw new IllegalArgumentException("Account not found: " + accountId);
            }
            CloudAccount account = accounts.get(0);

            // Resolve temporary credentials
            AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();

            // 3. Execute Scan (Pass credentials)
            List<ProwlerFinding> findings = runInternalScan(region, credentials, serviceGroups);

            // 4. Save Results & Update Status
            redisCacheService.put(findingsKey, findings, 1440); // Cache findings for 24 hours

            // Force refresh of combined security findings
            redisCacheService.evict(securityServiceCacheKey);
            logger.info("Evicted stale security cache: {}", securityServiceCacheKey);

            status.put("status", "COMPLETED");
            status.put("endTime", LocalDateTime.now().toString());
            status.put("message", "Scan completed. Found " + findings.size() + " issues.");
            status.put("findingsCount", findings.size());
            redisCacheService.put(statusKey, status, 60);

            logger.info("Async Prowler scan finished for account {}. Findings: {}", accountId, findings.size());

        } catch (Exception e) {
            logger.error("Async Prowler scan failed for account {}", accountId, e);
            Map<String, Object> status = new HashMap<>();
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("endTime", LocalDateTime.now().toString());
            redisCacheService.put(statusKey, status, 60);
        }
    }

    public Map<String, Object> getScanStatus(String accountId) {
        String statusKey = "prowler_status_" + accountId;
        return redisCacheService.get(statusKey, new TypeReference<Map<String, Object>>() {
        })
                .orElse(Map.of("status", "UNKNOWN", "message", "No scan running."));
    }

    public List<ProwlerFinding> getCachedFindings(String accountId) {
        String findingsKey = "prowler_findings_" + accountId;
        return redisCacheService.get(findingsKey, new TypeReference<List<ProwlerFinding>>() {
        })
                .orElse(Collections.emptyList());
    }

    public List<ProwlerFinding> runScan(String accountId, String region, String... serviceGroups) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);
        AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
        return runInternalScan(region, credentials, serviceGroups);
    }

    private List<ProwlerFinding> runInternalScan(String region, AwsCredentials credentials, String... serviceGroups) {
        String uniqueId = UUID.randomUUID().toString();
        String outputDir = System.getProperty("java.io.tmpdir");
        if (outputDir.endsWith(File.separator))
            outputDir = outputDir.substring(0, outputDir.length() - 1);
        String filenameBase = "prowler-" + uniqueId;

        String executable;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (configuredProwlerPath != null && !configuredProwlerPath.trim().isEmpty()) {
            executable = configuredProwlerPath;
        } else {
            executable = isWindows ? "prowler.exe" : "prowler";
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("aws");

        // ✅ UPDATE: Removed "--region" flag to allow Prowler to scan ALL available
        // regions.
        // command.add("--region");
        // command.add(region);

        command.add("--quiet");

        if (serviceGroups.length > 0) {
            command.add("--services");
            Collections.addAll(command, serviceGroups);
        }
        command.add("--output-modes");
        command.add("json");
        command.add("--output-filename");
        command.add(filenameBase);
        command.add("--output-directory");
        command.add(outputDir);

        ProcessBuilder builder = new ProcessBuilder(command);

        // Inject Credentials
        Map<String, String> env = builder.environment();
        env.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
        env.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
        if (credentials instanceof AwsSessionCredentials) {
            env.put("AWS_SESSION_TOKEN", ((AwsSessionCredentials) credentials).sessionToken());
        }

        // We still set this as a default entry point, but Prowler will now iterate over
        // all regions
        env.put("AWS_DEFAULT_REGION", region);
        env.remove("AWS_PROFILE");

        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[Prowler]: {}", line);
                }
            }

            // ✅ UPDATE: Increased timeout to 60 minutes for multi-region scans
            boolean finished = process.waitFor(60, TimeUnit.MINUTES);
            if (!finished) {
                process.destroy();
                throw new RuntimeException("Prowler scan timed out after 60 minutes");
            }

            File dir = new File(outputDir);
            File[] matchingFiles = dir.listFiles((d, name) -> name.startsWith(filenameBase) && name.endsWith(".json"));

            if (matchingFiles != null && matchingFiles.length > 0) {
                File jsonFile = matchingFiles[0];

                if (jsonFile.length() == 0) {
                    Files.deleteIfExists(jsonFile.toPath());
                    throw new RuntimeException("Prowler output file is empty. The process likely crashed.");
                }

                List<ProwlerFinding> findings = objectMapper.readValue(jsonFile,
                        new TypeReference<List<ProwlerFinding>>() {
                        });
                Files.deleteIfExists(jsonFile.toPath());
                return findings;
            } else {
                logger.warn("No Prowler output JSON found at {} with base {}", outputDir, filenameBase);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            throw new RuntimeException("Prowler Execution Failed: " + e.getMessage(), e);
        }
    }
}