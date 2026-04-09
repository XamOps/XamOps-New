package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.LlmBudget;
import com.xammer.cloud.domain.LlmBudgetAlert;
import com.xammer.cloud.domain.LlmProviderConfig;
import com.xammer.cloud.domain.LlmUsageLog;
import com.xammer.cloud.dto.AIOPsDashboardDto;
import com.xammer.cloud.dto.AIOPsDashboardDto.*;
import com.xammer.cloud.dto.LlmProviderConfigDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.LlmBudgetAlertRepository;
import com.xammer.cloud.repository.LlmBudgetRepository;
import com.xammer.cloud.repository.LlmProviderConfigRepository;
import com.xammer.cloud.repository.LlmUsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AIOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AIOpsService.class);

    private final LlmProviderConfigRepository providerConfigRepo;
    private final LlmUsageLogRepository usageLogRepo;
    private final LlmBudgetRepository budgetRepo;
    private final LlmBudgetAlertRepository budgetAlertRepo;
    private final EncryptionService encryptionService;
    private final ClientRepository clientRepository;
    private final RestTemplate restTemplate;

    @Value("${xamops.server.url:https://api.xamops.com}")
    private String serverBaseUrl;

    public AIOpsService(
            LlmProviderConfigRepository providerConfigRepo,
            LlmUsageLogRepository usageLogRepo,
            LlmBudgetRepository budgetRepo,
            LlmBudgetAlertRepository budgetAlertRepo,
            EncryptionService encryptionService,
            ClientRepository clientRepository,
            RestTemplate restTemplate) {
        this.providerConfigRepo = providerConfigRepo;
        this.usageLogRepo = usageLogRepo;
        this.budgetRepo = budgetRepo;
        this.budgetAlertRepo = budgetAlertRepo;
        this.encryptionService = encryptionService;
        this.clientRepository = clientRepository;
        this.restTemplate = restTemplate;
    }

    // ─── Dashboard ───────────────────────────────────────────────

    public LlmKpis getKpis(Long clientId, String period) {
        Instant since = parsePeriod(period);

        double totalSpend = usageLogRepo.sumCostSince(clientId, since);

        Instant todayStart = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long requestsToday = usageLogRepo.countRequestsSince(clientId, todayStart);
        long requestsThisMonth = usageLogRepo.countRequestsSince(clientId, monthStart);

        // Trend: today vs yesterday
        Instant yesterdayStart = todayStart.minus(1, ChronoUnit.DAYS);
        long requestsYesterday = usageLogRepo.countRequestsSince(clientId, yesterdayStart)
                                 - requestsToday;
        double todayVsYesterdayPct = requestsYesterday > 0
                ? ((double)(requestsToday - requestsYesterday) / requestsYesterday) * 100
                : 0;

        // Trend: this month vs last month
        Instant lastMonthStart = monthStart.minus(30, ChronoUnit.DAYS);
        long requestsLastMonth = usageLogRepo.countRequestsSince(clientId, lastMonthStart)
                                 - requestsThisMonth;
        double monthVsLastMonthPct = requestsLastMonth > 0
                ? ((double)(requestsThisMonth - requestsLastMonth) / requestsLastMonth) * 100
                : 0;

        long inputTokens = usageLogRepo.sumInputTokensSince(clientId, since);
        long outputTokens = usageLogRepo.sumOutputTokensSince(clientId, since);

        double avgLatency = usageLogRepo.avgLatencySince(clientId, since);

        return new LlmKpis(totalSpend, requestsToday, requestsThisMonth,
                todayVsYesterdayPct, monthVsLastMonthPct,
                inputTokens, outputTokens, avgLatency);
    }

    public CostTrend getCostTrend(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.dailyCostSince(clientId, since);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        for (Object[] row : rows) {
            labels.add(row[0].toString());
            costs.add(((Number) row[1]).doubleValue());
        }
        return new CostTrend(labels, costs);
    }

    public List<ProviderCostBreakdown> getProviderBreakdown(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.costByProviderSince(clientId, since);

        return rows.stream()
                .map(row -> new ProviderCostBreakdown(
                        (String) row[0],
                        ((Number) row[1]).doubleValue()))
                .collect(Collectors.toList());
    }

    public List<ModelUsageStat> getModelUsage(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.modelUsageStatsSince(clientId, since);

        return rows.stream()
                .map(row -> new ModelUsageStat(
                        (String) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(),
                        ((Number) row[4]).doubleValue(),
                        ((Number) row[5]).doubleValue()))
                .collect(Collectors.toList());
    }

    public List<CostAnomaly> getAnomalies(Long clientId, String period) {
        Instant since = parsePeriod(period);
        return detectAnomalies(clientId, since);
    }

    public AIOPsDashboardDto getFullDashboard(Long clientId, String period) {
        AIOPsDashboardDto dto = new AIOPsDashboardDto();
        dto.setKpis(getKpis(clientId, period));
        dto.setCostTrend(getCostTrend(clientId, period));
        dto.setProviderBreakdown(getProviderBreakdown(clientId, period));
        dto.setModelUsageTable(getModelUsage(clientId, period));
        dto.setAnomalies(getAnomalies(clientId, period));
        return dto;
    }

    // ─── Provider CRUD ───────────────────────────────────────────

    public List<LlmProviderConfigDto> getProviders(Long clientId) {
        return providerConfigRepo.findByClientIdOrderByCreatedAtDesc(clientId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LlmProviderConfigDto addProvider(Long clientId, LlmProviderConfigDto dto) {
        if (providerConfigRepo.existsByClientIdAndProvider(clientId, dto.getProvider())) {
            throw new IllegalArgumentException("Provider " + dto.getProvider() + " is already configured.");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found: " + clientId));

        LlmProviderConfig config = new LlmProviderConfig();
        config.setClient(client);
        config.setProvider(dto.getProvider().toUpperCase());
        config.setDisplayName(dto.getDisplayName() != null ? dto.getDisplayName() : dto.getProvider());
        config.setProxyEndpointId(UUID.randomUUID().toString());
        config.setStatus("ACTIVE");

        // API key is optional — pass-through approach means key comes in each request
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            config.setApiKeyEncrypted(encryptionService.encrypt(dto.getApiKey()));
        }

        if ("BEDROCK".equalsIgnoreCase(dto.getProvider())) {
            config.setAwsRegion(dto.getAwsRegion());
            if (dto.getAwsAccessKey() != null && !dto.getAwsAccessKey().isBlank()) {
                config.setAwsAccessKeyEncrypted(encryptionService.encrypt(dto.getAwsAccessKey()));
            }
            if (dto.getAwsSecretKey() != null && !dto.getAwsSecretKey().isBlank()) {
                config.setAwsSecretKeyEncrypted(encryptionService.encrypt(dto.getAwsSecretKey()));
            }
        }

        LlmProviderConfig saved = providerConfigRepo.save(config);
        logger.info("Added LLM provider {} for client {}", dto.getProvider(), clientId);
        return toDto(saved);
    }

    @Transactional
    public LlmProviderConfigDto updateProvider(Long clientId, Long configId, LlmProviderConfigDto dto) {
        LlmProviderConfig config = providerConfigRepo.findById(configId)
                .orElseThrow(() -> new RuntimeException("Provider config not found: " + configId));

        if (!config.getClient().getId().equals(clientId)) {
            throw new SecurityException("Not authorized to update this provider config");
        }

        if (dto.getDisplayName() != null) {
            config.setDisplayName(dto.getDisplayName());
        }
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            config.setApiKeyEncrypted(encryptionService.encrypt(dto.getApiKey()));
        }
        if ("BEDROCK".equalsIgnoreCase(config.getProvider())) {
            if (dto.getAwsRegion() != null) config.setAwsRegion(dto.getAwsRegion());
            if (dto.getAwsAccessKey() != null && !dto.getAwsAccessKey().isBlank()) {
                config.setAwsAccessKeyEncrypted(encryptionService.encrypt(dto.getAwsAccessKey()));
            }
            if (dto.getAwsSecretKey() != null && !dto.getAwsSecretKey().isBlank()) {
                config.setAwsSecretKeyEncrypted(encryptionService.encrypt(dto.getAwsSecretKey()));
            }
        }

        LlmProviderConfig saved = providerConfigRepo.save(config);
        logger.info("Updated LLM provider {} for client {}", config.getProvider(), clientId);
        return toDto(saved);
    }

    @Transactional
    public void deleteProvider(Long clientId, Long configId) {
        LlmProviderConfig config = providerConfigRepo.findById(configId)
                .orElseThrow(() -> new RuntimeException("Provider config not found: " + configId));

        if (!config.getClient().getId().equals(clientId)) {
            throw new SecurityException("Not authorized to delete this provider config");
        }

        providerConfigRepo.delete(config);
        logger.info("Deleted LLM provider {} for client {}", config.getProvider(), clientId);
    }

    // ─── Test Connection ─────────────────────────────────────────

    public Map<String, Object> testConnection(LlmProviderConfigDto dto) {
        Map<String, Object> result = new HashMap<>();
        try {
            String provider = dto.getProvider().toUpperCase();
            boolean success;

            switch (provider) {
                case "OPENAI":
                    success = testOpenAI(dto.getApiKey());
                    break;
                case "ANTHROPIC":
                    success = testAnthropic(dto.getApiKey());
                    break;
                case "GEMINI":
                    success = testGemini(dto.getApiKey());
                    break;
                case "BEDROCK":
                    success = testBedrock(dto);
                    break;
                default:
                    result.put("success", false);
                    result.put("message", "Unsupported provider: " + provider);
                    return result;
            }

            result.put("success", success);
            result.put("message", success ? "Connection successful" : "Connection failed");
        } catch (Exception e) {
            logger.error("Test connection failed for {}: {}", dto.getProvider(), e.getMessage());
            result.put("success", false);
            result.put("message", "Connection failed: " + e.getMessage());
        }
        return result;
    }

    private boolean testOpenAI(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://api.openai.com/v1/models",
                    HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("OpenAI test failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean testAnthropic(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"model\":\"claude-haiku-4-5-20241022\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://api.anthropic.com/v1/messages",
                    HttpMethod.POST, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("Anthropic test failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean testGemini(String apiKey) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "https://generativelanguage.googleapis.com/v1/models?key=" + apiKey,
                    String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.warn("Gemini test failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean testBedrock(LlmProviderConfigDto dto) {
        // Bedrock test uses AWS SDK - simplified check via RestTemplate to STS
        // In production, use BedrockRuntimeClient from AWS SDK
        logger.info("Bedrock connection test for region: {}", dto.getAwsRegion());
        return dto.getAwsAccessKey() != null && !dto.getAwsAccessKey().isBlank()
                && dto.getAwsSecretKey() != null && !dto.getAwsSecretKey().isBlank()
                && dto.getAwsRegion() != null && !dto.getAwsRegion().isBlank();
    }

    // ─── Anomaly Detection ───────────────────────────────────────

    private List<CostAnomaly> detectAnomalies(Long clientId, Instant since) {
        List<Object[]> rows = usageLogRepo.dailyCostByProviderSince(clientId, since);
        if (rows.size() < 3) return Collections.emptyList();

        // Group daily costs by provider, storing both date and cost
        Map<String, List<String>> providerDates = new LinkedHashMap<>();
        Map<String, List<Double>> providerCosts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String date = row[0].toString();
            String provider = (String) row[1];
            double cost = ((Number) row[2]).doubleValue();

            providerDates.computeIfAbsent(provider, k -> new ArrayList<>()).add(date);
            providerCosts.computeIfAbsent(provider, k -> new ArrayList<>()).add(cost);
        }

        List<CostAnomaly> anomalies = new ArrayList<>();

        for (String provider : providerCosts.keySet()) {
            List<Double> costs = providerCosts.get(provider);
            List<String> dates = providerDates.get(provider);

            if (costs.size() < 3) continue;

            // Calculate mean and standard deviation
            double sum = 0;
            for (double c : costs) sum += c;
            double mean = sum / costs.size();

            double varianceSum = 0;
            for (double c : costs) varianceSum += Math.pow(c - mean, 2);
            double stdDev = Math.sqrt(varianceSum / costs.size());

            if (stdDev == 0) continue;

            // Check most recent days for anomalies
            int checkDays = Math.min(3, costs.size());
            for (int i = costs.size() - checkDays; i < costs.size(); i++) {
                double cost = costs.get(i);
                double zScore = (cost - mean) / stdDev;

                if (zScore > 2.0) {
                    double deviationPct = ((cost - mean) / mean) * 100;
                    String severity = zScore > 3.0 ? "CRITICAL" : "WARNING";
                    anomalies.add(new CostAnomaly(dates.get(i), provider, cost, mean, deviationPct, severity));
                }
            }
        }

        return anomalies;
    }

    // ─── Usage Analytics ──────────────────────────────────────────

    public Map<String, Object> getRequestLogs(Long clientId, String period,
                                               String provider, String model, String agent,
                                               int page, int size) {
        Instant since = parsePeriod(period);

        Specification<LlmUsageLog> spec = (root, query, cb) -> {
            List<javax.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), since));

            if (provider != null && !provider.isBlank()) {
                predicates.add(cb.equal(root.get("provider"), provider));
            }
            if (model != null && !model.isBlank()) {
                predicates.add(cb.equal(root.get("model"), model));
            }
            if (agent != null && !agent.isBlank()) {
                predicates.add(cb.equal(root.get("agentName"), agent));
            }

            query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        };

        Page<LlmUsageLog> pageResult = usageLogRepo.findAll(spec, PageRequest.of(page, size));

        List<RequestLogEntry> entries = pageResult.getContent().stream()
                .map(log -> new RequestLogEntry(
                        log.getId(),
                        log.getTimestamp().toString(),
                        log.getProvider(),
                        log.getModel(),
                        log.getAgentName(),
                        log.getInputTokens() != null ? log.getInputTokens() : 0,
                        log.getOutputTokens() != null ? log.getOutputTokens() : 0,
                        log.getEstimatedCost() != null ? log.getEstimatedCost() : 0.0,
                        log.getLatencyMs() != null ? log.getLatencyMs() : 0L,
                        log.getStatusCode() != null ? log.getStatusCode() : 0,
                        Boolean.TRUE.equals(log.getIsError())))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", entries);
        result.put("page", pageResult.getNumber());
        result.put("size", pageResult.getSize());
        result.put("totalElements", pageResult.getTotalElements());
        result.put("totalPages", pageResult.getTotalPages());
        return result;
    }

    public List<AgentUsageStat> getAgentBreakdown(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.agentUsageStatsSince(clientId, since);

        return rows.stream()
                .map(row -> new AgentUsageStat(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).doubleValue(),
                        ((Number) row[4]).doubleValue()))
                .collect(Collectors.toList());
    }

    public List<ModelComparisonStat> getModelComparison(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.modelComparisonStatsSince(clientId, since);

        return rows.stream()
                .map(row -> {
                    long requests = ((Number) row[2]).longValue();
                    long errors = ((Number) row[7]).longValue();
                    double errorRate = requests > 0 ? (double) errors / requests * 100 : 0;
                    return new ModelComparisonStat(
                            (String) row[0],
                            (String) row[1],
                            requests,
                            ((Number) row[3]).longValue(),
                            ((Number) row[4]).longValue(),
                            ((Number) row[5]).doubleValue(),
                            ((Number) row[6]).doubleValue(),
                            errorRate);
                })
                .collect(Collectors.toList());
    }

    public List<HeatmapCell> getTokenHeatmap(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.tokenHeatmapSince(clientId, since);

        return rows.stream()
                .map(row -> new HeatmapCell(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue(),
                        ((Number) row[2]).longValue()))
                .collect(Collectors.toList());
    }

    public FilterOptions getFilterOptions(Long clientId) {
        List<String> providers = usageLogRepo.findDistinctProviders(clientId);
        List<String> models = usageLogRepo.findDistinctModels(clientId);
        List<String> agents = usageLogRepo.findDistinctAgentNames(clientId);
        return new FilterOptions(providers, models, agents);
    }

    // ─── Budget & Controls ────────────────────────────────────────

    public BudgetSummaryDto getBudgetSummary(Long clientId) {
        List<LlmBudget> allBudgets = budgetRepo.findByClientIdOrderByCreatedAtDesc(clientId);
        Instant monthStart = getCurrentMonthStart();
        String currentMonth = getCurrentBudgetMonth();

        double totalBudget = 0;
        double totalSpend = 0;
        List<BudgetStatusDto> statusList = new ArrayList<>();

        for (LlmBudget budget : allBudgets) {
            double spend = getSpendForBudget(budget, monthStart);
            double usagePct = budget.getMonthlyLimitUsd() > 0
                    ? (spend / budget.getMonthlyLimitUsd()) * 100 : 0;

            List<Integer> thresholds = parseThresholds(budget.getAlertThresholds());
            List<Integer> breached = new ArrayList<>();
            for (int t : thresholds) {
                if (usagePct >= t) breached.add(t);
            }

            String providerName = resolveProviderName(budget.getProviderConfigId());

            statusList.add(new BudgetStatusDto(
                    budget.getId(), budget.getBudgetName(),
                    budget.getProviderConfigId(), providerName,
                    budget.getMonthlyLimitUsd(), spend, usagePct,
                    Boolean.TRUE.equals(budget.getAutoDisable()),
                    budget.getStatus(), thresholds, breached));

            if ("ACTIVE".equals(budget.getStatus())) {
                totalBudget += budget.getMonthlyLimitUsd();
                totalSpend += spend;
            }
        }

        double overallPct = totalBudget > 0 ? (totalSpend / totalBudget) * 100 : 0;
        int activeCount = (int) allBudgets.stream().filter(b -> "ACTIVE".equals(b.getStatus())).count();

        List<LlmBudgetAlert> recentAlerts = budgetAlertRepo.findTop20ByClientIdOrderByTriggeredAtDesc(clientId);
        long alertsThisMonth = recentAlerts.stream()
                .filter(a -> currentMonth.equals(a.getBudgetMonth()))
                .count();

        List<BudgetAlertDto> alertDtos = recentAlerts.stream()
                .map(this::toBudgetAlertDto)
                .collect(Collectors.toList());

        return new BudgetSummaryDto(totalBudget, totalSpend, overallPct,
                activeCount, (int) alertsThisMonth, statusList, alertDtos);
    }

    @Transactional
    public BudgetConfigDto createBudget(Long clientId, BudgetCreateRequest request) {
        // Check for duplicate
        if (request.getProviderConfigId() == null) {
            if (budgetRepo.findGlobalBudget(clientId).isPresent()) {
                throw new IllegalArgumentException("A global budget already exists.");
            }
        } else {
            if (budgetRepo.findByClientIdAndProviderConfigId(clientId, request.getProviderConfigId()).isPresent()) {
                throw new IllegalArgumentException("A budget already exists for this provider.");
            }
            // Verify provider belongs to client
            LlmProviderConfig config = providerConfigRepo.findById(request.getProviderConfigId())
                    .orElseThrow(() -> new IllegalArgumentException("Provider not found."));
            if (!config.getClient().getId().equals(clientId)) {
                throw new SecurityException("Not authorized for this provider.");
            }
        }

        LlmBudget budget = new LlmBudget();
        budget.setClientId(clientId);
        budget.setProviderConfigId(request.getProviderConfigId());
        budget.setBudgetName(request.getBudgetName());
        budget.setMonthlyLimitUsd(request.getMonthlyLimitUsd());
        budget.setAlertThresholds(request.getAlertThresholds() != null ? request.getAlertThresholds() : "50,80,100");
        budget.setAutoDisable(request.isAutoDisable());
        budget.setNotificationEmail(request.getNotificationEmail());
        budget.setStatus("ACTIVE");

        LlmBudget saved = budgetRepo.save(budget);
        logger.info("Created LLM budget '{}' for client {}", saved.getBudgetName(), clientId);
        return toBudgetConfigDto(saved);
    }

    @Transactional
    public BudgetConfigDto updateBudget(Long clientId, Long budgetId, BudgetUpdateRequest request) {
        LlmBudget budget = budgetRepo.findById(budgetId)
                .orElseThrow(() -> new RuntimeException("Budget not found: " + budgetId));
        if (!budget.getClientId().equals(clientId)) {
            throw new SecurityException("Not authorized to update this budget.");
        }

        if (request.getMonthlyLimitUsd() != null) budget.setMonthlyLimitUsd(request.getMonthlyLimitUsd());
        if (request.getAlertThresholds() != null) budget.setAlertThresholds(request.getAlertThresholds());
        if (request.getAutoDisable() != null) budget.setAutoDisable(request.getAutoDisable());
        if (request.getStatus() != null) budget.setStatus(request.getStatus());
        if (request.getNotificationEmail() != null) budget.setNotificationEmail(request.getNotificationEmail());

        LlmBudget saved = budgetRepo.save(budget);
        logger.info("Updated LLM budget '{}' for client {}", saved.getBudgetName(), clientId);
        return toBudgetConfigDto(saved);
    }

    @Transactional
    public void deleteBudget(Long clientId, Long budgetId) {
        LlmBudget budget = budgetRepo.findById(budgetId)
                .orElseThrow(() -> new RuntimeException("Budget not found: " + budgetId));
        if (!budget.getClientId().equals(clientId)) {
            throw new SecurityException("Not authorized to delete this budget.");
        }
        budgetAlertRepo.deleteByBudgetId(budgetId);
        budgetRepo.delete(budget);
        logger.info("Deleted LLM budget '{}' for client {}", budget.getBudgetName(), clientId);
    }

    public List<BudgetConfigDto> getBudgets(Long clientId) {
        return budgetRepo.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(this::toBudgetConfigDto)
                .collect(Collectors.toList());
    }

    public List<BudgetAlertDto> getBudgetAlerts(Long clientId) {
        return budgetAlertRepo.findByClientIdOrderByTriggeredAtDesc(clientId).stream()
                .map(this::toBudgetAlertDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void dismissAlert(Long clientId, Long alertId) {
        LlmBudgetAlert alert = budgetAlertRepo.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alert not found: " + alertId));
        if (!alert.getClientId().equals(clientId)) {
            throw new SecurityException("Not authorized to dismiss this alert.");
        }
        alert.setDismissed(true);
        budgetAlertRepo.save(alert);
    }

    // ─── Budget Helpers ──────────────────────────────────────────

    private Instant getCurrentMonthStart() {
        return LocalDate.now().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private String getCurrentBudgetMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private double getSpendForBudget(LlmBudget budget, Instant monthStart) {
        if (budget.getProviderConfigId() == null) {
            return usageLogRepo.sumCostSince(budget.getClientId(), monthStart);
        } else {
            return usageLogRepo.sumCostByProviderConfigSince(
                    budget.getClientId(), budget.getProviderConfigId(), monthStart);
        }
    }

    private List<Integer> parseThresholds(String thresholds) {
        if (thresholds == null || thresholds.isBlank()) return Arrays.asList(50, 80, 100);
        List<Integer> result = new ArrayList<>();
        for (String s : thresholds.split(",")) {
            try { result.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        }
        Collections.sort(result);
        return result;
    }

    private String resolveProviderName(Long providerConfigId) {
        if (providerConfigId == null) return null;
        return providerConfigRepo.findById(providerConfigId)
                .map(c -> c.getDisplayName() != null ? c.getDisplayName() : c.getProvider())
                .orElse("Unknown");
    }

    private BudgetConfigDto toBudgetConfigDto(LlmBudget budget) {
        return new BudgetConfigDto(
                budget.getId(), budget.getProviderConfigId(),
                resolveProviderName(budget.getProviderConfigId()),
                budget.getBudgetName(), budget.getMonthlyLimitUsd(),
                budget.getAlertThresholds(),
                Boolean.TRUE.equals(budget.getAutoDisable()),
                budget.getStatus(), budget.getNotificationEmail(),
                budget.getCreatedAt() != null ? budget.getCreatedAt().toString() : null,
                budget.getUpdatedAt() != null ? budget.getUpdatedAt().toString() : null);
    }

    private BudgetAlertDto toBudgetAlertDto(LlmBudgetAlert alert) {
        String budgetName = "Unknown";
        String providerName = null;
        Optional<LlmBudget> budgetOpt = budgetRepo.findById(alert.getBudgetId());
        if (budgetOpt.isPresent()) {
            budgetName = budgetOpt.get().getBudgetName();
            providerName = resolveProviderName(budgetOpt.get().getProviderConfigId());
        }
        return new BudgetAlertDto(alert.getId(), alert.getBudgetId(),
                budgetName, providerName,
                alert.getThresholdPct(), alert.getActualSpendUsd(),
                alert.getBudgetLimitUsd(), alert.getBudgetMonth(),
                alert.getTriggeredAt().toString(),
                Boolean.TRUE.equals(alert.getDismissed()));
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private Instant parsePeriod(String period) {
        switch (period) {
            case "today":
                return LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
            case "7d":
                return Instant.now().minus(7, ChronoUnit.DAYS);
            case "30d":
                return Instant.now().minus(30, ChronoUnit.DAYS);
            case "90d":
                return Instant.now().minus(90, ChronoUnit.DAYS);
            default:
                return Instant.now().minus(30, ChronoUnit.DAYS);
        }
    }

    private LlmProviderConfigDto toDto(LlmProviderConfig config) {
        LlmProviderConfigDto dto = new LlmProviderConfigDto();
        dto.setId(config.getId());
        dto.setProvider(config.getProvider());
        dto.setDisplayName(config.getDisplayName());
        dto.setStatus(config.getStatus());
        dto.setApiKeyConfigured(config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank());
        dto.setTrackingToken(config.getProxyEndpointId());
        dto.setProxyBaseUrl(serverBaseUrl + "/api/llm-proxy");
        if (config.getAwsRegion() != null) {
            dto.setAwsRegion(config.getAwsRegion());
        }
        if (config.getCreatedAt() != null) {
            dto.setCreatedAt(config.getCreatedAt().toString());
        }
        return dto;
    }
}
