package com.xammer.cloud.xampulse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseExceptionHandler;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.query.*;
import com.xammer.cloud.xampulse.service.stream.TailTicketData;
import com.xammer.cloud.xampulse.service.stream.TailTicketService;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A live-tail ticket issued while impersonating must remember the real admin; no admin marker means no ticket. */
class XamPulseLogsControllerImpersonationTest {

    private static final long MINE = 100L;
    private static final long FOREIGN = 200L;

    private static final class MemoryStore implements TicketStore {
        final Map<String, String> data = new HashMap<>();
        public void put(String k, String v, Duration ttl) { data.put(k, v); }
        public Optional<String> getAndDelete(String k) { return Optional.ofNullable(data.remove(k)); }
        public Optional<String> get(String k) { return Optional.ofNullable(data.get(k)); }
        public boolean putIfAbsent(String k, String v, Duration ttl) { return data.putIfAbsent(k, v) == null; }
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final MemoryStore store = new MemoryStore();
    private TailTicketService tickets;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        XamPulseProperties props = new XamPulseProperties();
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(MINE);
        when(access.requireAccess(any(), eq(MINE))).thenReturn(mine);
        when(access.requireAccess(any(), eq(FOREIGN))).thenThrow(new AccessDeniedException("no"));
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));
        QueryScopeFactory scopes = new QueryScopeFactory(guard, props);
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        tickets = new TailTicketService(store, mapper);
        XamPulseLogsController controller = new XamPulseLogsController(new LogQueryService(scopes, executor, props),
                new LogFacetService(scopes, executor), new LogPatternService(scopes, executor),
                new LogContextService(guard, executor), scopes, tickets, new AuditService(mock(com.xammer.cloud.xampulse.repository.XpAuditRepository.class)), props);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new XamPulseExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        ClientUserDetails user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    private static String body(long account) {
        Instant to = Instant.now();
        return "{\"scope\":{\"accountIds\":[" + account + "],\"from\":\"" + to.minus(Duration.ofHours(1)) + "\",\"to\":\"" + to
                + "\"},\"query\":\"error\"}";
    }

    private String ticketFrom(String json) throws Exception {
        return mapper.readTree(json).get("ticket").asText();
    }

    @Test
    void ordinaryTicketCarriesNoActor() throws Exception {
        String res = mvc.perform(post("/api/xamops/xampulse/logs/tail/tickets").contentType(MediaType.APPLICATION_JSON).content(body(MINE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        TailTicketData data = tickets.consume(ticketFrom(res), 10L).orElseThrow();
        assertNull(data.actorUserId());
    }

    @Test
    void impersonatedTicketCarriesTheAdminFromTheSession() throws Exception {
        ImpersonationContext.setImpersonatedUserId(10L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("X-ORIGINAL-ADMIN-ID", 99L);
        String res = mvc.perform(post("/api/xamops/xampulse/logs/tail/tickets").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body(MINE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        TailTicketData data = tickets.consume(ticketFrom(res), 99L).orElseThrow();
        assertEquals(99L, data.actorUserId());
        assertEquals(10L, data.userId());
    }

    @Test
    void impersonationWithoutAnAdminMarkerIsRefused() throws Exception {
        ImpersonationContext.setImpersonatedUserId(10L);
        mvc.perform(post("/api/xamops/xampulse/logs/tail/tickets").contentType(MediaType.APPLICATION_JSON).content(body(MINE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("XP_FORBIDDEN"));
        assertTrue(store.data.isEmpty(), "no ticket may be stored");
    }

    @Test
    void foreignAccountIsStillA404WhenImpersonating() throws Exception {
        ImpersonationContext.setImpersonatedUserId(10L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("X-ORIGINAL-ADMIN-ID", 99L);
        mvc.perform(post("/api/xamops/xampulse/logs/tail/tickets").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body(FOREIGN)))
                .andExpect(status().isNotFound());
    }
}
