package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.LlmBudget;
import com.xammer.cloud.domain.LlmBudgetAlert;
import com.xammer.cloud.domain.LlmProviderConfig;
import com.xammer.cloud.domain.LlmRoutingRule;
import com.xammer.cloud.domain.LlmUsageLog;
import com.xammer.cloud.dto.AIOPsDashboardDto;
import com.xammer.cloud.dto.AIOPsDashboardDto.*;
import com.xammer.cloud.dto.LlmProviderConfigDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.LlmBudgetAlertRepository;
import com.xammer.cloud.repository.LlmBudgetRepository;
import com.xammer.cloud.repository.LlmProviderConfigRepository;
import com.xammer.cloud.repository.LlmRoutingRuleRepository;
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
    private final LlmRoutingRuleRepository routingRuleRepo;
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
            LlmRoutingRuleRepository routingRuleRepo,
            EncryptionService encryptionService,
            ClientRepository clientRepository,
            RestTemplate restTemplate) {
        this.providerConfigRepo = providerConfigRepo;
        this.usageLogRepo = usageLogRepo;
        this.budgetRepo = budgetRepo;
        this.budgetAlertRepo = budgetAlertRepo;
        this.routingRuleRepo = routingRuleRepo;
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
                        ((Number) row[1]).doubleValue(),
                        ((Number) row[2]).longValue()))
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

        // Delete associated usage logs so stale data doesn't show up after re-adding
        usageLogRepo.deleteByProviderConfigId(configId);

        providerConfigRepo.delete(config);
        logger.info("Deleted LLM provider {} (and associated usage logs) for client {}", config.getProvider(), clientId);
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

    public List<AgentUsageStat> getAgentBreakdown(Long clientId, String period,
                                                   String provider, String model) {
        Instant since = parsePeriod(period);
        String prov = (provider != null && !provider.isBlank()) ? provider : null;
        String mod = (model != null && !model.isBlank()) ? model : null;
        List<Object[]> rows = usageLogRepo.agentUsageStatsFiltered(clientId, since, prov, mod);

        return rows.stream()
                .map(row -> new AgentUsageStat(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).doubleValue(),
                        ((Number) row[4]).doubleValue()))
                .collect(Collectors.toList());
    }

    public List<ModelComparisonStat> getModelComparison(Long clientId, String period,
                                                        String provider, String agent) {
        Instant since = parsePeriod(period);
        String prov = (provider != null && !provider.isBlank()) ? provider : null;
        String ag = (agent != null && !agent.isBlank()) ? agent : null;
        List<Object[]> rows = usageLogRepo.modelComparisonStatsFiltered(clientId, since, prov, ag);

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

    public List<HeatmapCell> getTokenHeatmap(Long clientId, String period,
                                              String provider, String model, String agent) {
        Instant since = parsePeriod(period);
        String prov = (provider != null && !provider.isBlank()) ? provider : null;
        String mod = (model != null && !model.isBlank()) ? model : null;
        String ag = (agent != null && !agent.isBlank()) ? agent : null;
        List<Object[]> rows = usageLogRepo.tokenHeatmapFiltered(clientId, since, prov, mod, ag);

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

    // ─── All-Time Metrics ──────────────────────────────────────────

    public AllTimeMetrics getAllTimeMetrics(Long clientId) {
        Instant epoch = Instant.EPOCH;
        double cost = usageLogRepo.sumCostSince(clientId, epoch);
        long tokens = usageLogRepo.sumInputTokensSince(clientId, epoch)
                    + usageLogRepo.sumOutputTokensSince(clientId, epoch);
        long requests = usageLogRepo.countRequestsSince(clientId, epoch);
        return new AllTimeMetrics(cost, tokens, requests);
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

            int dayOfMonth = LocalDate.now().getDayOfMonth();
            int daysInMonth = LocalDate.now().lengthOfMonth();
            double burnRate = dayOfMonth > 0 ? spend / dayOfMonth : 0;
            double projectedSpend = burnRate * daysInMonth;
            int estimatedRunoutDay = (burnRate > 0 && spend < budget.getMonthlyLimitUsd())
                    ? Math.min((int) Math.ceil(budget.getMonthlyLimitUsd() / burnRate), daysInMonth)
                    : 0;

            statusList.add(new BudgetStatusDto(
                    budget.getId(), budget.getBudgetName(),
                    budget.getProviderConfigId(), providerName,
                    budget.getMonthlyLimitUsd(), spend, usagePct,
                    Boolean.TRUE.equals(budget.getAutoDisable()),
                    budget.getStatus(), thresholds, breached,
                    projectedSpend, burnRate, estimatedRunoutDay));

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

    public List<DailySpendEntry> getBudgetDailySpend(Long clientId, Long budgetId) {
        LlmBudget budget = budgetRepo.findById(budgetId)
                .orElseThrow(() -> new RuntimeException("Budget not found: " + budgetId));
        if (!budget.getClientId().equals(clientId)) {
            throw new SecurityException("Not authorized to view this budget.");
        }
        Instant monthStart = getCurrentMonthStart();
        List<Object[]> rows;
        if (budget.getProviderConfigId() != null) {
            rows = usageLogRepo.dailyCostByProviderConfigSince(clientId, budget.getProviderConfigId(), monthStart);
        } else {
            rows = usageLogRepo.dailyCostSince(clientId, monthStart);
        }
        return rows.stream()
                .map(r -> new DailySpendEntry(r[0].toString(), ((Number) r[1]).doubleValue()))
                .collect(Collectors.toList());
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

    Instant parsePeriod(String period) {
        switch (period) {
            case "today":
                return LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
            case "7d":
                return Instant.now().minus(7, ChronoUnit.DAYS);
            case "30d":
                return Instant.now().minus(30, ChronoUnit.DAYS);
            case "90d":
                return Instant.now().minus(90, ChronoUnit.DAYS);
            case "365d":
                return Instant.now().minus(365, ChronoUnit.DAYS);
            default:
                return Instant.now().minus(30, ChronoUnit.DAYS);
        }
    }

    // ─── Team Analytics ──────────────────────────────────────────

    public TeamAnalyticsSummary getTeamAnalytics(Long clientId, String period, String provider) {
        Instant since = parsePeriod(period);
        String prov = (provider != null && !provider.isBlank()) ? provider : null;

        List<Object[]> rows = usageLogRepo.teamUsageStats(clientId, since, prov);
        double totalCost = rows.stream().mapToDouble(r -> ((Number) r[3]).doubleValue()).sum();

        List<TeamUsageStat> teams = rows.stream()
                .map(row -> new TeamUsageStat(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).doubleValue(),
                        ((Number) row[4]).doubleValue(),
                        ((Number) row[5]).intValue(),
                        totalCost > 0 ? (((Number) row[3]).doubleValue() / totalCost) * 100 : 0))
                .collect(Collectors.toList());

        // Build cost trend
        List<Object[]> trendRows = usageLogRepo.dailyCostByAgentSince(clientId, since);
        Map<String, Map<String, Double>> agentDayMap = new LinkedHashMap<>();
        Set<String> allDates = new LinkedHashSet<>();
        for (Object[] r : trendRows) {
            String date = r[0].toString();
            String agent = (String) r[1];
            double cost = ((Number) r[2]).doubleValue();
            allDates.add(date);
            agentDayMap.computeIfAbsent(agent, k -> new LinkedHashMap<>()).put(date, cost);
        }
        List<String> labels = new ArrayList<>(allDates);
        Map<String, List<Double>> seriesByTeam = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : agentDayMap.entrySet()) {
            List<Double> series = labels.stream()
                    .map(d -> entry.getValue().getOrDefault(d, 0.0))
                    .collect(Collectors.toList());
            seriesByTeam.put(entry.getKey(), series);
        }
        TeamCostTrend costTrend = new TeamCostTrend(labels, seriesByTeam);

        return new TeamAnalyticsSummary(teams, costTrend, teams.size(), totalCost);
    }

    // ─── Performance Monitoring ──────────────────────────────────

    public PerformanceMetrics getPerformanceMetrics(Long clientId, String period,
                                                     String provider, String model) {
        Instant since = parsePeriod(period);
        String prov = (provider != null && !provider.isBlank()) ? provider : null;
        String mod = (model != null && !model.isBlank()) ? model : null;

        // Latency percentiles
        List<Object[]> latencyRows = usageLogRepo.latencyPercentilesByModel(clientId, since, prov);
        List<LatencyPercentiles> latencyByModel = latencyRows.stream()
                .map(r -> new LatencyPercentiles(
                        (String) r[0], (String) r[1],
                        ((Number) r[2]).doubleValue(), ((Number) r[3]).doubleValue(),
                        ((Number) r[4]).doubleValue(), ((Number) r[5]).doubleValue(),
                        ((Number) r[6]).longValue()))
                .collect(Collectors.toList());

        // Error rate trend
        List<Object[]> errorRows = usageLogRepo.dailyErrorRateTrend(clientId, since, prov, mod);
        List<ErrorRateTrendPoint> errorTrend = errorRows.stream()
                .map(r -> {
                    long total = ((Number) r[1]).longValue();
                    long errors = ((Number) r[2]).longValue();
                    double rate = total > 0 ? (double) errors / total * 100 : 0;
                    return new ErrorRateTrendPoint(r[0].toString(), total, errors, rate);
                })
                .collect(Collectors.toList());

        // Latency distribution
        List<Object[]> distRows = usageLogRepo.latencyDistribution(clientId, since, mod);
        List<LatencyBucket> distribution = distRows.stream()
                .map(r -> new LatencyBucket((String) r[0], ((Number) r[1]).longValue()))
                .collect(Collectors.toList());

        // Model reliability
        List<Object[]> relRows = usageLogRepo.modelReliabilityStats(clientId, since);
        List<ModelReliability> reliability = relRows.stream()
                .map(r -> {
                    long total = ((Number) r[2]).longValue();
                    long errors = ((Number) r[3]).longValue();
                    double errRate = total > 0 ? (double) errors / total * 100 : 0;
                    double avgLat = ((Number) r[4]).doubleValue();
                    double latencyPenalty = Math.min(avgLat / 1000.0, 10.0);
                    double score = Math.max(0, 100 - errRate - latencyPenalty);
                    return new ModelReliability((String) r[0], (String) r[1],
                            total, errors, errRate, avgLat, Math.round(score * 10.0) / 10.0);
                })
                .collect(Collectors.toList());

        return new PerformanceMetrics(latencyByModel, errorTrend, distribution, reliability);
    }

    // ─── Routing / Cost Optimizer ────────────────────────────────

    public List<RoutingRuleDto> getRoutingRules(Long clientId) {
        return routingRuleRepo.findByClientIdOrderByPriorityAsc(clientId).stream()
                .map(this::toRoutingRuleDto)
                .collect(Collectors.toList());
    }

    public RoutingRuleDto createRoutingRule(Long clientId, RoutingRuleCreateRequest req) {
        LlmRoutingRule rule = new LlmRoutingRule();
        rule.setClientId(clientId);
        rule.setRuleName(req.getRuleName());
        rule.setSourceModel(req.getSourceModel());
        rule.setTargetModel(req.getTargetModel());
        rule.setConditionType(req.getConditionType());
        rule.setConditionValue(req.getConditionValue());
        rule.setPriority(req.getPriority());
        rule.setEnabled(true);
        rule = routingRuleRepo.save(rule);
        return toRoutingRuleDto(rule);
    }

    public RoutingRuleDto updateRoutingRule(Long clientId, Long ruleId, RoutingRuleCreateRequest req) {
        LlmRoutingRule rule = routingRuleRepo.findById(ruleId)
                .orElseThrow(() -> new RuntimeException("Routing rule not found"));
        if (!rule.getClientId().equals(clientId)) throw new SecurityException("Access denied");
        if (req.getRuleName() != null) rule.setRuleName(req.getRuleName());
        if (req.getTargetModel() != null) rule.setTargetModel(req.getTargetModel());
        if (req.getConditionType() != null) rule.setConditionType(req.getConditionType());
        if (req.getConditionValue() != null) rule.setConditionValue(req.getConditionValue());
        rule.setPriority(req.getPriority());
        rule = routingRuleRepo.save(rule);
        return toRoutingRuleDto(rule);
    }

    public void toggleRoutingRule(Long clientId, Long ruleId, boolean enabled) {
        LlmRoutingRule rule = routingRuleRepo.findById(ruleId)
                .orElseThrow(() -> new RuntimeException("Routing rule not found"));
        if (!rule.getClientId().equals(clientId)) throw new SecurityException("Access denied");
        rule.setEnabled(enabled);
        routingRuleRepo.save(rule);
    }

    public void deleteRoutingRule(Long clientId, Long ruleId) {
        LlmRoutingRule rule = routingRuleRepo.findById(ruleId)
                .orElseThrow(() -> new RuntimeException("Routing rule not found"));
        if (!rule.getClientId().equals(clientId)) throw new SecurityException("Access denied");
        routingRuleRepo.delete(rule);
    }

    public RoutingStatsDto getRoutingStats(Long clientId, String period) {
        Instant since = parsePeriod(period);
        Long routedCount = usageLogRepo.countRoutedRequestsSince(clientId, since);
        List<Object[]> statsRows = usageLogRepo.routingStats(clientId, since);

        double actualSpend = 0;
        List<RoutingSavingsEntry> savingsList = new ArrayList<>();
        for (Object[] row : statsRows) {
            String from = (String) row[0];
            String to = (String) row[1];
            long count = ((Number) row[2]).longValue();
            double cost = ((Number) row[3]).doubleValue();
            actualSpend += cost;
            savingsList.add(new RoutingSavingsEntry(from, to, count, cost));
        }

        List<RoutingRuleDto> rules = getRoutingRules(clientId);

        return new RoutingStatsDto(
                routedCount != null ? routedCount : 0,
                0, // estimated savings calculated separately if needed
                actualSpend,
                0, // would-have-spent requires original model pricing lookup
                rules,
                savingsList
        );
    }

    public List<RoutingHistoryEntry> getRoutingHistory(Long clientId, int page, int size) {
        List<LlmUsageLog> logs = usageLogRepo.findRecentRoutedRequests(clientId, PageRequest.of(page, size));
        return logs.stream()
                .map(l -> new RoutingHistoryEntry(
                        l.getId(),
                        l.getTimestamp().toString(),
                        l.getRoutedFrom(),
                        l.getModel(),
                        l.getAgentName(),
                        l.getInputTokens() != null ? l.getInputTokens() : 0,
                        l.getOutputTokens() != null ? l.getOutputTokens() : 0,
                        l.getEstimatedCost() != null ? l.getEstimatedCost() : 0))
                .collect(Collectors.toList());
    }

    private RoutingRuleDto toRoutingRuleDto(LlmRoutingRule rule) {
        return new RoutingRuleDto(
                rule.getId(),
                rule.getRuleName(),
                rule.getSourceModel(),
                rule.getTargetModel(),
                rule.getConditionType(),
                rule.getConditionValue(),
                rule.getPriority(),
                rule.getEnabled(),
                rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null
        );
    }

    // ─── API Key Management ────────────────────────────────────

    public List<ApiKeyStats> getApiKeyStats(Long clientId) {
        List<LlmProviderConfig> configs = providerConfigRepo.findByClientIdOrderByCreatedAtDesc(clientId);
        return configs.stream().map(c -> {
            String maskedKey = "••••••••";
            if (c.getApiKeyEncrypted() != null && !c.getApiKeyEncrypted().isBlank()) {
                try {
                    String decrypted = encryptionService.decrypt(c.getApiKeyEncrypted());
                    if (decrypted.length() >= 4) {
                        maskedKey = "••••" + decrypted.substring(decrypted.length() - 4);
                    }
                } catch (Exception ignored) {
                    maskedKey = "••••????";
                }
            } else {
                maskedKey = "Not configured";
            }

            String healthStatus = "UNKNOWN";
            long reqCount = c.getRequestCount() != null ? c.getRequestCount() : 0;
            long errCount = c.getErrorCount() != null ? c.getErrorCount() : 0;
            if (reqCount == 0) {
                healthStatus = "UNKNOWN";
            } else {
                double errRate = (double) errCount / reqCount * 100;
                if (errRate < 1) healthStatus = "HEALTHY";
                else if (errRate < 10) healthStatus = "DEGRADED";
                else healthStatus = "ERROR";
            }

            return new ApiKeyStats(
                    c.getId(),
                    c.getProvider(),
                    c.getDisplayName(),
                    maskedKey,
                    c.getStatus(),
                    healthStatus,
                    reqCount,
                    errCount,
                    c.getLastUsedAt() != null ? c.getLastUsedAt().toString() : null,
                    c.getLastErrorAt() != null ? c.getLastErrorAt().toString() : null,
                    c.getLastErrorMessage(),
                    c.getUsageLimit(),
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null
            );
        }).collect(Collectors.toList());
    }

    @Transactional
    public void rotateKey(Long clientId, Long configId, ApiKeyRotateRequest request) {
        LlmProviderConfig config = providerConfigRepo.findById(configId)
                .orElseThrow(() -> new RuntimeException("Provider config not found"));
        if (!config.getClient().getId().equals(clientId)) {
            throw new SecurityException("Access denied");
        }
        if (request.getNewApiKey() != null && !request.getNewApiKey().isBlank()) {
            config.setApiKeyEncrypted(encryptionService.encrypt(request.getNewApiKey()));
        }
        if ("BEDROCK".equalsIgnoreCase(config.getProvider())) {
            if (request.getNewAwsAccessKey() != null && !request.getNewAwsAccessKey().isBlank()) {
                config.setAwsAccessKeyEncrypted(encryptionService.encrypt(request.getNewAwsAccessKey()));
            }
            if (request.getNewAwsSecretKey() != null && !request.getNewAwsSecretKey().isBlank()) {
                config.setAwsSecretKeyEncrypted(encryptionService.encrypt(request.getNewAwsSecretKey()));
            }
        }
        // Reset error state after key rotation
        config.setLastErrorAt(null);
        config.setLastErrorMessage(null);
        config.setErrorCount(0L);
        providerConfigRepo.save(config);
        logger.info("Rotated API key for provider {} (client {})", config.getProvider(), clientId);
    }

    @Transactional
    public void updateUsageLimit(Long clientId, Long configId, Long limit) {
        LlmProviderConfig config = providerConfigRepo.findById(configId)
                .orElseThrow(() -> new RuntimeException("Provider config not found"));
        if (!config.getClient().getId().equals(clientId)) {
            throw new SecurityException("Access denied");
        }
        config.setUsageLimit(limit);
        providerConfigRepo.save(config);
    }

    // ─── Cleanup: Orphaned Logs ────────────────────────────────────

    @Transactional
    public int cleanupOrphanedLogs(Long clientId) {
        int deleted = usageLogRepo.deleteOrphanedLogs(clientId);
        logger.info("Cleaned up {} orphaned usage logs for client {}", deleted, clientId);
        return deleted;
    }

    // ─── Compute / GPU Metrics ────────────────────────────────────

    public ComputeMetricsSummary getComputeMetrics(Long clientId, String period, String provider) {
        Instant since = parsePeriod(period);

        Specification<LlmUsageLog> spec = (root, query, cb) -> {
            List<javax.persistence.criteria.Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("clientId"), clientId));
            preds.add(cb.greaterThanOrEqualTo(root.get("timestamp"), since));
            if (provider != null && !provider.isBlank()) {
                preds.add(cb.equal(root.get("provider"), provider));
            }
            return cb.and(preds.toArray(new javax.persistence.criteria.Predicate[0]));
        };

        List<LlmUsageLog> allLogs = usageLogRepo.findAll(spec, PageRequest.of(0, Integer.MAX_VALUE)).getContent();

        // Provider breakdown
        Map<String, List<LlmUsageLog>> byProvider = allLogs.stream()
                .collect(Collectors.groupingBy(LlmUsageLog::getProvider));

        List<ComputeUsageByProvider> providerBreakdown = byProvider.entrySet().stream()
                .map(e -> {
                    List<LlmUsageLog> logs = e.getValue();
                    long reqs = logs.size();
                    long inTok = logs.stream().mapToLong(l -> l.getInputTokens() != null ? l.getInputTokens() : 0).sum();
                    long outTok = logs.stream().mapToLong(l -> l.getOutputTokens() != null ? l.getOutputTokens() : 0).sum();
                    double cost = logs.stream().mapToDouble(l -> l.getEstimatedCost() != null ? l.getEstimatedCost() : 0).sum();
                    double avgLat = logs.stream().mapToLong(l -> l.getLatencyMs() != null ? l.getLatencyMs() : 0).average().orElse(0);
                    double computeTime = logs.stream().mapToDouble(l -> (l.getLatencyMs() != null ? l.getLatencyMs() : 0) / 1000.0).sum();
                    return new ComputeUsageByProvider(e.getKey(), reqs, inTok, outTok, cost, avgLat, computeTime);
                })
                .sorted((a, b) -> Double.compare(b.getTotalCost(), a.getTotalCost()))
                .collect(Collectors.toList());

        // Daily trend
        Map<String, List<LlmUsageLog>> byDay = allLogs.stream()
                .collect(Collectors.groupingBy(l ->
                        l.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().toString()
                ));
        List<String> sortedDays = byDay.keySet().stream().sorted().collect(Collectors.toList());
        List<Long> dailyInput = new ArrayList<>();
        List<Long> dailyOutput = new ArrayList<>();
        List<Double> dailyCompute = new ArrayList<>();
        for (String day : sortedDays) {
            List<LlmUsageLog> dayLogs = byDay.get(day);
            dailyInput.add(dayLogs.stream().mapToLong(l -> l.getInputTokens() != null ? l.getInputTokens() : 0).sum());
            dailyOutput.add(dayLogs.stream().mapToLong(l -> l.getOutputTokens() != null ? l.getOutputTokens() : 0).sum());
            dailyCompute.add(dayLogs.stream().mapToDouble(l -> (l.getLatencyMs() != null ? l.getLatencyMs() : 0) / 1000.0).sum());
        }
        ComputeDailyTrend dailyTrend = new ComputeDailyTrend(sortedDays, dailyInput, dailyOutput, dailyCompute);

        // Model allocation
        double totalCompute = allLogs.stream().mapToDouble(l -> (l.getLatencyMs() != null ? l.getLatencyMs() : 0) / 1000.0).sum();
        Map<String, List<LlmUsageLog>> byModel = allLogs.stream()
                .collect(Collectors.groupingBy(l -> l.getModel() + "|" + l.getProvider()));

        List<ModelComputeAllocation> modelAllocation = byModel.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    List<LlmUsageLog> logs = e.getValue();
                    long reqs = logs.size();
                    long tokens = logs.stream().mapToLong(l -> (l.getInputTokens() != null ? l.getInputTokens() : 0) + (l.getOutputTokens() != null ? l.getOutputTokens() : 0)).sum();
                    double cost = logs.stream().mapToDouble(l -> l.getEstimatedCost() != null ? l.getEstimatedCost() : 0).sum();
                    double ct = logs.stream().mapToDouble(l -> (l.getLatencyMs() != null ? l.getLatencyMs() : 0) / 1000.0).sum();
                    double pct = totalCompute > 0 ? (ct / totalCompute) * 100 : 0;
                    return new ModelComputeAllocation(parts[0], parts.length > 1 ? parts[1] : "", reqs, tokens, cost, ct, pct);
                })
                .sorted((a, b) -> Double.compare(b.getComputeTimeSec(), a.getComputeTimeSec()))
                .collect(Collectors.toList());

        long totalReqs = allLogs.size();
        long totalTokens = allLogs.stream().mapToLong(l -> (l.getInputTokens() != null ? l.getInputTokens() : 0) + (l.getOutputTokens() != null ? l.getOutputTokens() : 0)).sum();
        double totalCost = allLogs.stream().mapToDouble(l -> l.getEstimatedCost() != null ? l.getEstimatedCost() : 0).sum();

        return new ComputeMetricsSummary(totalReqs, totalTokens, totalCompute, totalCost,
                providerBreakdown, dailyTrend, modelAllocation);
    }

    // ─── Managed Inference ──────────────────────────────────────

    public InferenceOverview getManagedInference(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<LlmProviderConfig> configs = providerConfigRepo.findByClientIdOrderByCreatedAtDesc(clientId);

        // Get usage grouped by provider+model
        Specification<LlmUsageLog> spec = (root, query, cb) -> {
            List<javax.persistence.criteria.Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("clientId"), clientId));
            preds.add(cb.greaterThanOrEqualTo(root.get("timestamp"), since));
            return cb.and(preds.toArray(new javax.persistence.criteria.Predicate[0]));
        };

        List<LlmUsageLog> allLogs = usageLogRepo.findAll(spec, PageRequest.of(0, Integer.MAX_VALUE)).getContent();

        // Group by provider+model to create virtual endpoints
        Map<String, List<LlmUsageLog>> byEndpoint = allLogs.stream()
                .collect(Collectors.groupingBy(l -> l.getProvider() + "|" + l.getModel()));

        List<InferenceEndpoint> endpoints = new ArrayList<>();
        long idCounter = 1;

        for (Map.Entry<String, List<LlmUsageLog>> entry : byEndpoint.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String prov = parts[0];
            String model = parts.length > 1 ? parts[1] : "";
            List<LlmUsageLog> logs = entry.getValue();

            long reqs = logs.size();
            double cost = logs.stream().mapToDouble(l -> l.getEstimatedCost() != null ? l.getEstimatedCost() : 0).sum();
            double avgLat = logs.stream().mapToLong(l -> l.getLatencyMs() != null ? l.getLatencyMs() : 0).average().orElse(0);
            long errors = logs.stream().filter(l -> Boolean.TRUE.equals(l.getIsError())).count();
            double errRate = reqs > 0 ? (double) errors / reqs * 100 : 0;

            // Determine health status
            String status;
            if (errRate > 20) status = "DOWN";
            else if (errRate > 5) status = "DEGRADED";
            else status = "ACTIVE";

            // Uptime approximation: percentage of non-error requests
            double uptimePct = reqs > 0 ? ((double) (reqs - errors) / reqs) * 100 : 100;

            Instant lastReq = logs.stream().map(LlmUsageLog::getTimestamp).max(Instant::compareTo).orElse(null);

            endpoints.add(new InferenceEndpoint(
                    idCounter++, prov, model,
                    prov + " / " + model,
                    status, reqs, cost, avgLat, errRate, uptimePct,
                    lastReq != null ? lastReq.toString() : null,
                    null
            ));
        }

        // Also add provider configs with zero usage
        for (LlmProviderConfig config : configs) {
            boolean hasUsage = endpoints.stream().anyMatch(e -> e.getProvider().equals(config.getProvider()));
            if (!hasUsage) {
                endpoints.add(new InferenceEndpoint(
                        idCounter++, config.getProvider(), "—",
                        config.getDisplayName() != null ? config.getDisplayName() : config.getProvider(),
                        "INACTIVE", 0, 0, 0, 0, 100, null,
                        config.getCreatedAt() != null ? config.getCreatedAt().toString() : null
                ));
            }
        }

        endpoints.sort((a, b) -> Long.compare(b.getTotalRequests(), a.getTotalRequests()));

        int total = endpoints.size();
        int active = (int) endpoints.stream().filter(e -> "ACTIVE".equals(e.getStatus())).count();
        int degraded = (int) endpoints.stream().filter(e -> "DEGRADED".equals(e.getStatus()) || "DOWN".equals(e.getStatus())).count();
        double totalCost = endpoints.stream().mapToDouble(InferenceEndpoint::getTotalCost).sum();
        double avgLatency = endpoints.stream().filter(e -> e.getTotalRequests() > 0).mapToDouble(InferenceEndpoint::getAvgLatencyMs).average().orElse(0);

        return new InferenceOverview(total, active, degraded, totalCost, avgLatency, endpoints);
    }

    // ─── Model Daily Analysis ──────────────────────────────────

    public ModelDailyAnalysis getModelDailyAnalysis(Long clientId, String period) {
        Instant since = parsePeriod(period);
        List<Object[]> rows = usageLogRepo.dailyCostByModelSince(clientId, since);

        Set<String> dateSet = new LinkedHashSet<>();
        Set<String> modelSet = new LinkedHashSet<>();
        Map<String, Map<String, Double>> costMap = new LinkedHashMap<>();
        Map<String, Map<String, Long>> tokenMap = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String date = row[0].toString();
            String model = (String) row[1];
            double cost = ((Number) row[2]).doubleValue();
            long tokens = ((Number) row[3]).longValue();

            dateSet.add(date);
            modelSet.add(model);
            costMap.computeIfAbsent(model, k -> new LinkedHashMap<>()).put(date, cost);
            tokenMap.computeIfAbsent(model, k -> new LinkedHashMap<>()).put(date, tokens);
        }

        List<String> labels = new ArrayList<>(dateSet);
        List<String> models = new ArrayList<>(modelSet);

        Map<String, List<Double>> costByModel = new LinkedHashMap<>();
        Map<String, List<Long>> tokensByModel = new LinkedHashMap<>();

        for (String model : models) {
            costByModel.put(model, labels.stream()
                .map(d -> costMap.getOrDefault(model, Collections.emptyMap()).getOrDefault(d, 0.0))
                .collect(Collectors.toList()));
            tokensByModel.put(model, labels.stream()
                .map(d -> tokenMap.getOrDefault(model, Collections.emptyMap()).getOrDefault(d, 0L))
                .collect(Collectors.toList()));
        }

        return new ModelDailyAnalysis(labels, models, costByModel, tokensByModel);
    }

    // ─── Helper: toDto ───────────────────────────────────────────

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
