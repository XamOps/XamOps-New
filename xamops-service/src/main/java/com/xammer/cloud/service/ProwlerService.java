package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.compliance.ComplianceScanRecorder;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ProwlerService {

    private static final Logger logger = LoggerFactory.getLogger(ProwlerService.class);

    // Limits concurrent Prowler OS processes — each uses ~500MB-1GB RAM.
    // 2 permits = safe for 4 GB container; raise to 4 when infra is 8 GB+.
    private static final Semaphore SCAN_SEMAPHORE = new Semaphore(2, true);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisCacheService redisCacheService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository cachedDataRepository;
    private final AwsClientProvider awsClientProvider;
    private final ComplianceScanRecorder complianceScanRecorder;

    @Value("${prowler.executable.path:}")
    private String configuredProwlerPath;

    @Autowired
    public ProwlerService(RedisCacheService redisCacheService,
            CloudAccountRepository cloudAccountRepository,
            CachedDataRepository cachedDataRepository,
            AwsClientProvider awsClientProvider,
            ComplianceScanRecorder complianceScanRecorder) {
        this.redisCacheService = redisCacheService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.awsClientProvider = awsClientProvider;
        this.complianceScanRecorder = complianceScanRecorder;
    }

    /**
     * Write the scan into the ComplianceOps tables.
     *
     * Never allowed to fail the scan. The Redis + CachedData path above is what
     * SecurityPage reads and has to keep working; this call adds the historical record
     * ComplianceOps needs. If it throws, the scan is still successful and only the
     * history entry is missing, which is recoverable on the next run.
     */
    private void recordCompliance(String accountId, String provider, List<String> regions,
            String[] frameworks, List<ProwlerFinding> findings, Instant startedAt) {
        try {
            complianceScanRecorder.recordCompleted(accountId, provider, regions,
                    frameworks == null ? List.of() : Arrays.asList(frameworks), findings, startedAt);
        } catch (Exception e) {
            logger.error("Failed to record compliance scan history for {} — the scan itself "
                    + "succeeded and cached findings are intact", accountId, e);
        }
    }

    private void recordComplianceFailure(String accountId, String provider, List<String> regions,
            String[] frameworks, Instant startedAt, String error) {
        try {
            complianceScanRecorder.recordFailed(accountId, provider, regions,
                    frameworks == null ? List.of() : Arrays.asList(frameworks), startedAt, error);
        } catch (Exception e) {
            logger.error("Failed to record compliance scan failure for {}", accountId, e);
        }
    }

    /**
     * Trigger async Prowler scan across multiple regions with compliance frameworks.
     * Acquires a semaphore permit before running so at most 2 Prowler processes
     * run simultaneously — safe for 500-account queues on a 4 GB container.
     */
    @Async("prowlerTaskExecutor")
    public void triggerScanAsync(String accountId, List<String> regions, String[] serviceGroups,
            String[] complianceFrameworks) {
        SCAN_SEMAPHORE.acquireUninterruptibly();
        try {
            runAsyncScan(accountId, regions, serviceGroups, complianceFrameworks);
        } finally {
            SCAN_SEMAPHORE.release();
        }
    }

    /** Convenience overload — single region. */
    @Async("prowlerTaskExecutor")
    public void triggerScanAsync(String accountId, String region, String[] serviceGroups,
            String[] complianceFrameworks) {
        SCAN_SEMAPHORE.acquireUninterruptibly();
        try {
            runAsyncScan(accountId, List.of(region), serviceGroups, complianceFrameworks);
        } finally {
            SCAN_SEMAPHORE.release();
        }
    }

    /** Convenience overload — single region, no compliance frameworks. */
    @Async("prowlerTaskExecutor")
    public void triggerScanAsync(String accountId, String region, String... serviceGroups) {
        SCAN_SEMAPHORE.acquireUninterruptibly();
        try {
            runAsyncScan(accountId, List.of(region), serviceGroups, new String[0]);
        } finally {
            SCAN_SEMAPHORE.release();
        }
    }

    private void runAsyncScan(String accountId, List<String> regions, String[] serviceGroups,
            String[] complianceFrameworks) {
        String statusKey = "prowler_status_" + accountId;
        String findingsKey = "prowler_findings_" + accountId;
        String securityServiceCacheKey = "securityFindings-" + accountId;
        // Captured before any work so the trend plots when the account was observed,
        // not when a 40-minute scan happened to finish.
        Instant scanStartedAt = Instant.now();

        try {
            // 1. Update Status to RUNNING
            Map<String, Object> status = new HashMap<>();
            status.put("status", "RUNNING");
            status.put("startTime", LocalDateTime.now().toString());
            status.put("message", "Initializing Prowler scan for ALL regions...");
            redisCacheService.put(statusKey, status, 1440); // 1440 min (24 hrs)

            logger.info("Async Prowler scan started for account {}", accountId);

            // 2. Fetch Account and Credentials
            List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
            if (accounts.isEmpty()) {
                throw new IllegalArgumentException("Account not found: " + accountId);
            }
            CloudAccount account = accounts.get(0);

            // Resolve temporary credentials
            AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();

            // 3. Execute scan — one region at a time to keep memory bounded,
            // then merge all findings. Sequential is intentional: each Prowler
            // process uses ~500 MB; running them in parallel multiplies that.
            List<ProwlerFinding> findings = new ArrayList<>();
            for (String region : regions) {
                logger.info("Scanning region {} ({} of {}) for account {}",
                        region, regions.indexOf(region) + 1, regions.size(), accountId);
                status.put("message", "Scanning region " + region + " (" + (regions.indexOf(region) + 1) + "/" + regions.size() + ")...");
                redisCacheService.put(statusKey, status, 1440);
                try {
                    findings.addAll(runInternalScan(region, credentials, serviceGroups, complianceFrameworks));
                } catch (Exception regionEx) {
                    logger.error("Failed to scan region {} for account {}: {}", region, accountId, regionEx.getMessage());
                }
            }

            // 4. Save Results & Update Status
            redisCacheService.put(findingsKey, findings, 1440); // Cache findings for 24 hours in Redis

            // Persist to DB so findings survive Redis restarts
            try {
                String prowlerJson = objectMapper.writeValueAsString(findings);
                String dbProwlerKey = "PROWLER::" + accountId + "::LATEST";
                com.xammer.cloud.domain.CachedData prowlerData = cachedDataRepository.findById(dbProwlerKey)
                        .orElse(new com.xammer.cloud.domain.CachedData());
                prowlerData.setCacheKey(dbProwlerKey);
                prowlerData.setJsonData(prowlerJson);
                prowlerData.setLastUpdated(LocalDateTime.now());
                cachedDataRepository.save(prowlerData);
                logger.info("Persisted {} Prowler findings to DB for account {}", findings.size(), accountId);
            } catch (Exception dbEx) {
                logger.warn("Failed to persist Prowler findings to DB: {}", dbEx.getMessage());
            }

            recordCompliance(accountId, "AWS", regions, complianceFrameworks, findings, scanStartedAt);

            // Force refresh of combined security findings (Redis + DB snapshots)
            redisCacheService.evict(securityServiceCacheKey);
            // Also evict the DB snapshot used by SecurityController's fast-path cache
            String dbSnapshotKey = "security-" + accountId;
            cachedDataRepository.deleteById(dbSnapshotKey);
            // Evict the LATEST archive so SecurityService re-merges with new Prowler data
            String dbLatestKey = "SECURITY::" + accountId + "::LATEST";
            cachedDataRepository.deleteById(dbLatestKey);
            logger.info("Evicted stale security caches (Redis + DB): {}", accountId);

            status.put("status", "COMPLETED");
            status.put("endTime", LocalDateTime.now().toString());
            status.put("message", "Scan completed. Found " + findings.size() + " issues.");
            status.put("findingsCount", findings.size());
            redisCacheService.put(statusKey, status, 1440); // 1440 min (24 hrs)

            logger.info("Async Prowler scan finished for account {}. Findings: {}", accountId, findings.size());

        } catch (Exception e) {
            logger.error("Async Prowler scan failed for account {}", accountId, e);
            Map<String, Object> status = new HashMap<>();
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("endTime", LocalDateTime.now().toString());
            redisCacheService.put(statusKey, status, 1440); // 1440 min (24 hrs)
            recordComplianceFailure(accountId, "AWS", regions, complianceFrameworks,
                    scanStartedAt, e.getMessage());
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

        // 1. Try Redis first (fastest)
        Optional<List<ProwlerFinding>> redisFindings = redisCacheService.get(findingsKey,
                new TypeReference<List<ProwlerFinding>>() {
                });
        if (redisFindings.isPresent()) {
            logger.info("Loaded {} Prowler findings from Redis for account {}", redisFindings.get().size(), accountId);
            return redisFindings.get();
        }

        // 2. Fall back to DB (survives Redis/app restarts)
        String dbProwlerKey = "PROWLER::" + accountId + "::LATEST";
        Optional<com.xammer.cloud.domain.CachedData> dbData = cachedDataRepository.findById(dbProwlerKey);
        if (dbData.isPresent()) {
            try {
                List<ProwlerFinding> findings = objectMapper.readValue(
                        dbData.get().getJsonData(),
                        new TypeReference<List<ProwlerFinding>>() {
                        });
                logger.info("Loaded {} Prowler findings from DB for account {} (Redis miss)", findings.size(),
                        accountId);
                // Re-populate Redis for future calls
                redisCacheService.put(findingsKey, findings, 1440);
                return findings;
            } catch (Exception e) {
                logger.error("Failed to parse Prowler findings from DB for account {}: {}", accountId, e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // GCP Prowler Support
    // -------------------------------------------------------------------------

    @Async("prowlerTaskExecutor")
    public void triggerGcpScanAsync(String projectId, String[] complianceFrameworks) {
        String statusKey = "prowler_status_gcp_" + projectId;
        String findingsKey = "prowler_findings_gcp_" + projectId;
        Instant scanStartedAt = Instant.now();

        try {
            Map<String, Object> status = new HashMap<>();
            status.put("status", "RUNNING");
            status.put("startTime", LocalDateTime.now().toString());
            status.put("message", "Initializing Prowler GCP scan...");
            redisCacheService.put(statusKey, status, 1440);

            logger.info("Async Prowler GCP scan started for project {}", projectId);

            Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(projectId);
            if (accountOpt.isEmpty()) {
                throw new IllegalArgumentException("GCP account not found for project: " + projectId);
            }
            CloudAccount account = accountOpt.get();

            List<ProwlerFinding> findings = runInternalGcpScan(account, complianceFrameworks);

            redisCacheService.put(findingsKey, findings, 1440);

            try {
                String json = objectMapper.writeValueAsString(findings);
                String dbKey = "PROWLER::GCP::" + projectId + "::LATEST";
                com.xammer.cloud.domain.CachedData data = cachedDataRepository.findById(dbKey)
                        .orElse(new com.xammer.cloud.domain.CachedData());
                data.setCacheKey(dbKey);
                data.setJsonData(json);
                data.setLastUpdated(LocalDateTime.now());
                cachedDataRepository.save(data);
                logger.info("Persisted {} GCP Prowler findings to DB for project {}", findings.size(), projectId);
            } catch (Exception dbEx) {
                logger.warn("Failed to persist GCP Prowler findings to DB: {}", dbEx.getMessage());
            }

            // GCP scans are project-scoped, so there is no region list to record.
            recordCompliance(projectId, "GCP", List.of(), complianceFrameworks, findings, scanStartedAt);

            status.put("status", "COMPLETED");
            status.put("endTime", LocalDateTime.now().toString());
            status.put("findingsCount", findings.size());
            status.put("message", "GCP scan completed. Found " + findings.size() + " issues.");
            redisCacheService.put(statusKey, status, 1440);

            logger.info("Async Prowler GCP scan finished for project {}. Findings: {}", projectId, findings.size());

        } catch (Exception e) {
            logger.error("Async Prowler GCP scan failed for project {}", projectId, e);
            Map<String, Object> status = new HashMap<>();
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("endTime", LocalDateTime.now().toString());
            redisCacheService.put(statusKey, status, 1440);
            recordComplianceFailure(projectId, "GCP", List.of(), complianceFrameworks,
                    scanStartedAt, e.getMessage());
        }
    }

    public Map<String, Object> getGcpScanStatus(String projectId) {
        return redisCacheService.get("prowler_status_gcp_" + projectId, new TypeReference<Map<String, Object>>() {})
                .orElse(Map.of("status", "UNKNOWN", "message", "No scan running."));
    }

    public List<ProwlerFinding> getCachedGcpFindings(String projectId) {
        String findingsKey = "prowler_findings_gcp_" + projectId;
        Optional<List<ProwlerFinding>> redis = redisCacheService.get(findingsKey, new TypeReference<List<ProwlerFinding>>() {});
        if (redis.isPresent()) return redis.get();

        String dbKey = "PROWLER::GCP::" + projectId + "::LATEST";
        Optional<com.xammer.cloud.domain.CachedData> dbData = cachedDataRepository.findById(dbKey);
        if (dbData.isPresent()) {
            try {
                List<ProwlerFinding> findings = objectMapper.readValue(dbData.get().getJsonData(), new TypeReference<List<ProwlerFinding>>() {});
                redisCacheService.put(findingsKey, findings, 1440);
                return findings;
            } catch (Exception e) {
                logger.error("Failed to parse GCP Prowler findings from DB: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private List<ProwlerFinding> runInternalGcpScan(CloudAccount account, String[] complianceFrameworks) {
        String uniqueId = UUID.randomUUID().toString();
        String outputDir = System.getProperty("java.io.tmpdir").replaceAll("[/\\\\]$", "");
        String filenameBase = "prowler-gcp-" + uniqueId;

        String executable = (configuredProwlerPath != null && !configuredProwlerPath.trim().isEmpty())
                ? configuredProwlerPath : "prowler";

        // Write service account key JSON to a temp file
        File keyFile = null;
        try {
            if (account.getGcpServiceAccountKey() != null && !account.getGcpServiceAccountKey().isBlank()) {
                keyFile = File.createTempFile("gcp-sa-key-", ".json");
                Files.writeString(keyFile.toPath(), account.getGcpServiceAccountKey());
            }

            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("gcp");
            command.add("--no-banner");
            command.add("--project-ids");
            command.add(account.getGcpProjectId());

            if (keyFile != null) {
                command.add("--credentials-file");
                command.add(keyFile.getAbsolutePath());
            }

            if (complianceFrameworks != null && complianceFrameworks.length > 0) {
                command.add("--compliance");
                Collections.addAll(command, complianceFrameworks);
            }

            command.add("--output-modes");
            command.add("json-ocsf");
            command.add("--output-filename");
            command.add(filenameBase);
            command.add("--output-directory");
            command.add(outputDir);

            return executeProwlerCommand(command, new HashMap<>(), outputDir, filenameBase);

        } catch (Exception e) {
            throw new RuntimeException("GCP Prowler scan failed: " + e.getMessage(), e);
        } finally {
            if (keyFile != null) keyFile.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Azure Prowler Support
    // -------------------------------------------------------------------------

    @Async("prowlerTaskExecutor")
    public void triggerAzureScanAsync(String subscriptionId, String[] complianceFrameworks) {
        String statusKey = "prowler_status_azure_" + subscriptionId;
        String findingsKey = "prowler_findings_azure_" + subscriptionId;
        Instant scanStartedAt = Instant.now();

        try {
            Map<String, Object> status = new HashMap<>();
            status.put("status", "RUNNING");
            status.put("startTime", LocalDateTime.now().toString());
            status.put("message", "Initializing Prowler Azure scan...");
            redisCacheService.put(statusKey, status, 1440);

            logger.info("Async Prowler Azure scan started for subscription {}", subscriptionId);

            Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId);
            if (accountOpt.isEmpty()) {
                throw new IllegalArgumentException("Azure account not found for subscription: " + subscriptionId);
            }
            CloudAccount account = accountOpt.get();

            List<ProwlerFinding> findings = runInternalAzureScan(account, complianceFrameworks);

            redisCacheService.put(findingsKey, findings, 1440);

            try {
                String json = objectMapper.writeValueAsString(findings);
                String dbKey = "PROWLER::AZURE::" + subscriptionId + "::LATEST";
                com.xammer.cloud.domain.CachedData data = cachedDataRepository.findById(dbKey)
                        .orElse(new com.xammer.cloud.domain.CachedData());
                data.setCacheKey(dbKey);
                data.setJsonData(json);
                data.setLastUpdated(LocalDateTime.now());
                cachedDataRepository.save(data);
                logger.info("Persisted {} Azure Prowler findings to DB for subscription {}", findings.size(), subscriptionId);
            } catch (Exception dbEx) {
                logger.warn("Failed to persist Azure Prowler findings to DB: {}", dbEx.getMessage());
            }

            // Azure scans are subscription-scoped; regions come back on each finding.
            recordCompliance(subscriptionId, "Azure", List.of(), complianceFrameworks,
                    findings, scanStartedAt);

            status.put("status", "COMPLETED");
            status.put("endTime", LocalDateTime.now().toString());
            status.put("findingsCount", findings.size());
            status.put("message", "Azure scan completed. Found " + findings.size() + " issues.");
            redisCacheService.put(statusKey, status, 1440);

            logger.info("Async Prowler Azure scan finished for subscription {}. Findings: {}", subscriptionId, findings.size());

        } catch (Exception e) {
            logger.error("Async Prowler Azure scan failed for subscription {}", subscriptionId, e);
            Map<String, Object> status = new HashMap<>();
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("endTime", LocalDateTime.now().toString());
            redisCacheService.put(statusKey, status, 1440);
            recordComplianceFailure(subscriptionId, "Azure", List.of(), complianceFrameworks,
                    scanStartedAt, e.getMessage());
        }
    }

    public Map<String, Object> getAzureScanStatus(String subscriptionId) {
        return redisCacheService.get("prowler_status_azure_" + subscriptionId, new TypeReference<Map<String, Object>>() {})
                .orElse(Map.of("status", "UNKNOWN", "message", "No scan running."));
    }

    public List<ProwlerFinding> getCachedAzureFindings(String subscriptionId) {
        String findingsKey = "prowler_findings_azure_" + subscriptionId;
        Optional<List<ProwlerFinding>> redis = redisCacheService.get(findingsKey, new TypeReference<List<ProwlerFinding>>() {});
        if (redis.isPresent()) return redis.get();

        String dbKey = "PROWLER::AZURE::" + subscriptionId + "::LATEST";
        Optional<com.xammer.cloud.domain.CachedData> dbData = cachedDataRepository.findById(dbKey);
        if (dbData.isPresent()) {
            try {
                List<ProwlerFinding> findings = objectMapper.readValue(dbData.get().getJsonData(), new TypeReference<List<ProwlerFinding>>() {});
                redisCacheService.put(findingsKey, findings, 1440);
                return findings;
            } catch (Exception e) {
                logger.error("Failed to parse Azure Prowler findings from DB: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private List<ProwlerFinding> runInternalAzureScan(CloudAccount account, String[] complianceFrameworks) {
        String uniqueId = UUID.randomUUID().toString();
        String outputDir = System.getProperty("java.io.tmpdir").replaceAll("[/\\\\]$", "");
        String filenameBase = "prowler-azure-" + uniqueId;

        String executable = (configuredProwlerPath != null && !configuredProwlerPath.trim().isEmpty())
                ? configuredProwlerPath : "prowler";

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("azure");
        command.add("--no-banner");
        // Service-principal auth: Prowler reads the client id/secret/tenant from
        // AZURE_* env vars (set below), NOT from --client-id/--client-secret flags
        // (those are unrecognized and abort the scan with exit code 2).
        // Do NOT pass --tenant-id here: that flag is reserved for --browser-auth
        // mode and, when combined with --sp-env-auth, makes Prowler think we want
        // browser auth and fail with AzureTenantIDNoBrowserAuthError. The tenant
        // is supplied purely via the AZURE_TENANT_ID env var below.
        command.add("--sp-env-auth");
        command.add("--subscription-ids");
        command.add(account.getAzureSubscriptionId());

        if (complianceFrameworks != null && complianceFrameworks.length > 0) {
            command.add("--compliance");
            Collections.addAll(command, complianceFrameworks);
        }

        command.add("--output-modes");
        command.add("json-ocsf");
        command.add("--output-filename");
        command.add(filenameBase);
        command.add("--output-directory");
        command.add(outputDir);

        Map<String, String> env = new HashMap<>();
        env.put("AZURE_CLIENT_ID", account.getAzureClientId());
        env.put("AZURE_CLIENT_SECRET", account.getAzureClientSecret());
        env.put("AZURE_TENANT_ID", account.getAzureTenantId());

        return executeProwlerCommand(command, env, outputDir, filenameBase);
    }

    // -------------------------------------------------------------------------
    // Shared execution helper (extracted from AWS runInternalScan)
    // -------------------------------------------------------------------------

    private List<ProwlerFinding> executeProwlerCommand(List<String> command, Map<String, String> extraEnv,
            String outputDir, String filenameBase) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(extraEnv);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.redirectErrorStream(true);

        logger.info("Executing Prowler command: {}", String.join(" ", command));

        try {
            Process process = builder.start();
            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[Prowler]: {}", line);
                    if (processOutput.length() < 2000) processOutput.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.MINUTES);
            if (!finished) {
                process.destroy();
                throw new RuntimeException("Prowler scan timed out after 60 minutes");
            }

            int exitCode = process.exitValue();
            logger.info("Prowler process exited with code: {}", exitCode);

            if (exitCode != 0 && exitCode != 3) {
                String output = processOutput.length() > 500
                        ? processOutput.substring(processOutput.length() - 500) : processOutput.toString();
                throw new RuntimeException("Prowler exited with code " + exitCode + ". Output: " + output);
            }

            File dir = new File(outputDir);
            File[] matchingFiles = dir.listFiles((d, name) -> name.startsWith(filenameBase) && name.contains(".json"));

            if (matchingFiles != null && matchingFiles.length > 0) {
                File jsonFile = matchingFiles[0];
                if (jsonFile.length() == 0) {
                    Files.deleteIfExists(jsonFile.toPath());
                    throw new RuntimeException("Prowler output file is empty.");
                }
                logger.info("Parsing Prowler output: {} ({} bytes)", jsonFile.getName(), jsonFile.length());
                List<ProwlerFinding> findings = objectMapper.readValue(jsonFile, new TypeReference<List<ProwlerFinding>>() {});
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

    public List<ProwlerFinding> runScan(String accountId, String region, String... serviceGroups) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);
        AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
        return runInternalScan(region, credentials, serviceGroups, new String[0]);
    }

    private List<ProwlerFinding> runInternalScan(String region, AwsCredentials credentials, String[] serviceGroups,
            String[] complianceFrameworks) {
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

        // Verify Prowler executable exists
        File prowlerFile = new File(executable);
        if (executable.contains(File.separator) && !prowlerFile.exists()) {
            throw new RuntimeException(
                    "Prowler executable not found at: " + executable
                            + ". Install Prowler with: pip install prowler");
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("aws");
        command.add("--no-banner");

        // Limit to specific regions to prevent OOM on constrained hosts (scanning all regions is ~20x memory)
        command.add("--region");
        command.add(region);

        // Prowler does NOT allow --compliance and --services together.
        // If compliance frameworks are specified, use --compliance only.
        // Otherwise, use --services for targeted service scans.
        if (complianceFrameworks != null && complianceFrameworks.length > 0) {
            command.add("--compliance");
            Collections.addAll(command, complianceFrameworks);
        } else if (serviceGroups.length > 0) {
            command.add("--services");
            Collections.addAll(command, serviceGroups);
        }
        command.add("--output-modes");
        command.add("json-ocsf");
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

        logger.info("Executing Prowler command: {}", String.join(" ", command));

        try {
            Process process = builder.start();

            // Capture output for better error diagnostics
            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("[Prowler]: {}", line);
                    if (processOutput.length() < 2000) {
                        processOutput.append(line).append("\n");
                    }
                }
            }

            // ✅ UPDATE: Increased timeout to 60 minutes for multi-region scans
            boolean finished = process.waitFor(60, TimeUnit.MINUTES);
            if (!finished) {
                process.destroy();
                throw new RuntimeException("Prowler scan timed out after 60 minutes");
            }

            int exitCode = process.exitValue();
            logger.info("Prowler process exited with code: {}", exitCode);

            if (exitCode != 0 && exitCode != 3) {
                // Exit code 3 means findings found (expected), other non-zero is error
                String output = processOutput.length() > 500
                        ? processOutput.substring(processOutput.length() - 500)
                        : processOutput.toString();
                throw new RuntimeException(
                        "Prowler exited with code " + exitCode + ". Output: " + output);
            }

            File dir = new File(outputDir);
            // Prowler v5 OCSF output: {filename}.ocsf.json
            File[] matchingFiles = dir.listFiles((d, name) -> name.startsWith(filenameBase) && name.contains(".json"));

            if (matchingFiles != null && matchingFiles.length > 0) {
                File jsonFile = matchingFiles[0];

                if (jsonFile.length() == 0) {
                    Files.deleteIfExists(jsonFile.toPath());
                    throw new RuntimeException("Prowler output file is empty. The process likely crashed.");
                }

                logger.info("Parsing Prowler output file: {} ({} bytes)", jsonFile.getName(), jsonFile.length());
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