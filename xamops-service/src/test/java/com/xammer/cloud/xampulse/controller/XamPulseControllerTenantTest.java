package com.xammer.cloud.xampulse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.repository.*;
import com.xammer.cloud.xampulse.service.alert.*;
import com.xammer.cloud.xampulse.service.archive.ArchiveSearchService;
import com.xammer.cloud.xampulse.service.insight.*;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.*;
import com.xammer.cloud.xampulse.service.stream.TailTicketService;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every controller family, hit with an account id that belongs to someone else (or does not exist): the answer must be
 * the same 404 XP_NOT_FOUND envelope and ClickHouse must never be queried. Uses the real services behind the controllers.
 */
class XamPulseControllerTenantTest {

    private static final long FOREIGN = 200L;
    private static final long MINE = 100L;

    private MockMvc mvc;
    private ClickHouseExecutor executor;

    private static final class MemoryStore implements TicketStore {
        final Map<String, String> data = new HashMap<>();
        public void put(String k, String v, Duration ttl) { data.put(k, v); }
        public Optional<String> getAndDelete(String k) { return Optional.ofNullable(data.remove(k)); }
        public Optional<String> get(String k) { return Optional.ofNullable(data.get(k)); }
        public boolean putIfAbsent(String k, String v, Duration ttl) { return data.putIfAbsent(k, v) == null; }
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        XamPulseProperties props = new XamPulseProperties();
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(MINE);
        when(access.requireAccess(any(), eq(MINE))).thenReturn(mine);
        when(access.requireAccess(any(), eq(FOREIGN))).thenThrow(new AccessDeniedException("Account not found or access denied."));
        when(access.requireAccess(any(), eq(99999L))).thenThrow(new AccessDeniedException("Account not found or access denied."));
        CloudAccountRepository accounts = mock(CloudAccountRepository.class);
        when(accounts.findByClientId(any())).thenReturn(List.of());
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, accounts);
        QueryScopeFactory scopes = new QueryScopeFactory(guard, props);
        executor = mock(ClickHouseExecutor.class);

        LogQueryService logs = new LogQueryService(scopes, executor, props);
        LogPatternService patterns = new LogPatternService(scopes, executor);
        EventQueryService events = new EventQueryService(scopes, executor);
        XpIncidentRepository incidentRepo = mock(XpIncidentRepository.class);
        IncidentService incidents = new IncidentService(incidentRepo, mock(XpIncidentEventRepository.class), guard, mapper);
        HealthScoreService health = new HealthScoreService();
        HostQueryService hosts = new HostQueryService(scopes, executor, mock(XpAgentRepository.class), health, incidents, logs, events);
        ProcessQueryService processes = new ProcessQueryService(scopes, executor);
        XpAccountConfigRepository archiveConfigs = mock(XpAccountConfigRepository.class);
        ArchiveSearchService archiveSearch = mock(ArchiveSearchService.class);
        OverviewService overview = new OverviewService(scopes, executor, hosts, incidents, mock(XpAccountConfigRepository.class),
                new NarrativeBuilder(), new AttentionRanker(), health);
        UsageMeteringService usage = new UsageMeteringService(scopes, executor, mock(XpUsageDailyRepository.class), mock(XpAccountConfigRepository.class));
        InsightFeedService insights = new InsightFeedService(scopes, executor, overview, patterns, usage, incidents, events,
                new ForecastService(), new WhatChangedService());
        RuleCompiler compiler = new RuleCompiler();
        AuditService audit = new AuditService(mock(XpAuditRepository.class));
        XpAlertRuleRepository ruleRepo = mock(XpAlertRuleRepository.class);
        AlertRuleService rules = new AlertRuleService(ruleRepo, mock(XpAlertStateRepository.class), guard, compiler, mapper);
        SilenceService silences = new SilenceService(mock(XpSilenceRepository.class), guard, mapper);
        ChannelService channels = new ChannelService(mock(XpChannelRepository.class), guard, mapper, new OutboundUrlGuard(), List.of());
        AlertPreviewService preview = new AlertPreviewService(scopes, executor, compiler);

        Object[] controllers = {
                new XamPulseLogsController(logs, new LogFacetService(scopes, executor), patterns, new LogContextService(guard, executor),
                        scopes, new TailTicketService(new MemoryStore(), mapper), audit, props),
                new XamPulseMetricsController(new MetricQueryService(scopes, executor), new MetricCatalogService(scopes, executor)),
                new XamPulseEventsController(events),
                new XamPulseUsageController(usage),
                new XamPulseContainersController(new ContainerQueryService(scopes, executor)),
                new XamPulseHostsController(hosts, processes),
                new XamPulseArchiveSearchController(guard, archiveConfigs, archiveSearch),
                new XamPulseOverviewController(overview),
                new XamPulseInsightsController(insights),
                new XamPulseViewsController(new SavedViewService(mock(XpSavedViewRepository.class), guard, mapper)),
                new XamPulseAlertsController(rules, preview, silences, channels, audit),
                new XamPulseIncidentsController(incidents, audit),
                new XamPulseAuditController(mock(XpAuditRepository.class), guard)};

        mvc = MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new XamPulseExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        ClientUserDetails user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static String scope(long id) {
        Instant to = Instant.now();
        return "{\"accountIds\":[" + id + "],\"from\":\"" + to.minus(Duration.ofHours(1)) + "\",\"to\":\"" + to + "\"}";
    }

    private static String window() {
        Instant to = Instant.now();
        return "from=" + to.minus(Duration.ofHours(1)) + "&to=" + to;
    }

    private static String rule(long id) {
        return "{\"name\":\"cpu\",\"enabled\":true,\"severity\":\"HIGH\",\"scope\":{\"accountIds\":[" + id + "]},"
                + "\"condition\":{\"type\":\"METRIC_THRESHOLD\",\"metric\":\"system.cpu.utilization\",\"agg\":\"avg\",\"window\":\"5m\",\"op\":\">\",\"value\":0.9,\"for\":\"5m\",\"noData\":\"KEEP\"}}";
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<MockHttpServletRequestBuilder> foreignRequests() {
        long f = FOREIGN;
        List<MockHttpServletRequestBuilder> r = new ArrayList<>();
        String api = "/api/xamops/xampulse";
        r.add(json(post(api + "/logs/search"), "{\"scope\":" + scope(f) + ",\"query\":\"error\"}"));
        r.add(json(post(api + "/logs/histogram"), "{\"scope\":" + scope(f) + ",\"query\":\"\"}"));
        r.add(json(post(api + "/logs/facets"), "{\"scope\":" + scope(f) + ",\"query\":\"\",\"fields\":[\"service\"]}"));
        r.add(json(post(api + "/logs/patterns"), "{\"scope\":" + scope(f) + ",\"query\":\"\"}"));
        r.add(json(post(api + "/logs/tail/tickets"), "{\"scope\":" + scope(f) + ",\"query\":\"error\"}"));
        // a row id minted for the foreign tenant, presented against that account
        r.add(get(api + "/logs/context").param("accountId", String.valueOf(f))
                .param("id", LogRowIdCodec.encode(f, 1767225600000L, "i-1", "12345")));
        r.add(json(post(api + "/metrics/query"), "{\"scope\":" + scope(f) + ",\"series\":[{\"metric\":\"system.cpu.utilization\",\"agg\":\"avg\"}]}"));
        r.add(get(api + "/metrics/catalog").param("accountId", String.valueOf(f)));
        r.add(get(api + "/events?accountIds=" + f + "&" + window()));
        r.add(get(api + "/usage").param("accountId", String.valueOf(f)));
        r.add(get(api + "/containers").param("accountIds", String.valueOf(f)));
        r.add(get(api + "/hosts").param("accountIds", String.valueOf(f)));
        r.add(get(api + "/hosts/i-1/summary?accountId=" + f + "&" + window()));
        r.add(get(api + "/hosts/i-1/processes?accountId=" + f));
        r.add(json(post(api + "/archive-searches"), "{\"scope\":" + scope(f) + ",\"query\":\"error\"}"));
        r.add(get(api + "/overview?accountIds=" + f + "&" + window()));
        r.add(get(api + "/health-score").param("accountId", String.valueOf(f)));
        r.add(get(api + "/insights/feed?accountIds=" + f + "&" + window()));
        r.add(get(api + "/insights/forecasts").param("accountId", String.valueOf(f)));
        r.add(get(api + "/views").param("accountId", String.valueOf(f)));
        r.add(json(post(api + "/views?accountId=" + f), "{\"name\":\"v\",\"kind\":\"LOGS\",\"shared\":false,\"state\":{}}"));
        r.add(delete(api + "/views/1").param("accountId", String.valueOf(f)));
        r.add(get(api + "/alerts/rules").param("accountIds", String.valueOf(f)));
        r.add(json(post(api + "/alerts/rules"), rule(f)));
        r.add(json(post(api + "/alerts/rules/preview"), rule(f)));
        r.add(json(put(api + "/alerts/rules/5"), rule(MINE).replace("\"name\"", "\"version\":0,\"name\"")));
        r.add(delete(api + "/alerts/rules/5"));
        r.add(get(api + "/alerts/silences").param("accountIds", String.valueOf(f)));
        r.add(json(post(api + "/alerts/silences"), "{\"accountId\":" + f + ",\"matcher\":{\"instanceIds\":[\"i-1\"]},\"startsAt\":\"2026-01-01T00:00:00Z\",\"endsAt\":\"2026-01-02T00:00:00Z\"}"));
        r.add(delete(api + "/alerts/silences/5"));
        r.add(get(api + "/alerts/channels").param("accountId", String.valueOf(f)));
        r.add(json(post(api + "/alerts/channels"), "{\"accountId\":" + f + ",\"kind\":\"EMAIL\",\"name\":\"n\",\"config\":{\"to\":[\"a@example.com\"]}}"));
        r.add(json(put(api + "/alerts/channels/5"), "{\"accountId\":" + MINE + ",\"kind\":\"EMAIL\",\"name\":\"n\",\"config\":{\"to\":[\"a@example.com\"]}}"));
        r.add(delete(api + "/alerts/channels/5"));
        r.add(post(api + "/alerts/channels/5/test"));
        r.add(get(api + "/alerts/incidents").param("accountIds", String.valueOf(f)));
        r.add(get(api + "/alerts/incidents/5"));
        r.add(json(post(api + "/alerts/incidents/5/ack"), "{}"));
        r.add(get(api + "/audit").param("accountId", String.valueOf(f)));
        return r;
    }

    @Test
    void everyControllerFamilyAnswersAForeignAccountWithTheUniformNotFoundAndNeverTouchesClickHouse() throws Exception {
        for (MockHttpServletRequestBuilder req : foreignRequests()) {
            mvc.perform(req)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("XP_NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("Not found."))
                    .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        }
        verifyNoInteractions(executor);
    }

    @Test
    void aNonexistentAccountIsIndistinguishableFromAForeignOne() throws Exception {
        String foreign = mvc.perform(post("/api/xamops/xampulse/logs/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(FOREIGN) + ",\"query\":\"\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(post("/api/xamops/xampulse/logs/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(99999) + ",\"query\":\"\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(strip(foreign), strip(missing));
    }

    private static String strip(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    @Test
    void ownAccountWithABadFilterIsABadFilterNotANotFound() throws Exception {
        mvc.perform(post("/api/xamops/xampulse/logs/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":" + scope(MINE) + ",\"query\":\"tenant_id:1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XP_BAD_FILTER"));
        verifyNoInteractions(executor);
    }

    @Test
    void rangeTooWideIsReportedWithTheLimit() throws Exception {
        Instant to = Instant.now();
        mvc.perform(post("/api/xamops/xampulse/logs/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":{\"accountIds\":[" + MINE + "],\"from\":\"" + to.minus(Duration.ofDays(40)) + "\",\"to\":\"" + to + "\"},\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("XP_QUERY_TOO_BROAD"))
                .andExpect(jsonPath("$.error.details.maxRangeDays").value(30));
    }
}
