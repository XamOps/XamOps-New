package com.xammer.billops.controller;

import com.xammer.billops.config.SecurityConfig;
import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import com.xammer.billops.security.CustomUserDetailsService;
import com.xammer.billops.security.TrialEnforcementFilter;
import com.xammer.billops.service.AdminAccessScope;
import com.xammer.billops.service.RcaService;
import com.xammer.billops.service.TicketService;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who can close and reopen a BillOps ticket over HTTP.
 *
 * <p>Regression guard: the ticket page offers "Close Ticket" to customers, but the endpoint used to
 * admit only BillOps admins, so every customer close got a 403 and the page showed "Error closing
 * ticket. Please try again." Closing is now open to any signed-in user, and the controller tells
 * the service whether the caller is an admin. Whether a customer can see the ticket is decided in
 * the service; {@code TicketServiceCloseTicketTest} covers that. Reopening stays admin-only.
 *
 * <p>Runs the real {@link SecurityConfig} chains through MockMvc with only collaborators mocked,
 * the same way {@code AdminCloudFrontControllerSecurityTest} does.
 */
@SpringJUnitWebConfig(TicketControllerCloseSecurityTest.TestConfig.class)
@TestPropertySource(properties = "app.impersonation.secret-key=" + TicketControllerCloseSecurityTest.SECRET)
class TicketControllerCloseSecurityTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final long TICKET_ID = 9L;
    private static final String CLOSE = "/api/billops/tickets/" + TICKET_ID + "/close";
    private static final String REOPEN = "/api/billops/tickets/" + TICKET_ID + "/reopen";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            TicketController.class})
    static class TestConfig {
    }

    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private RcaService rcaService;
    @MockitoBean
    private ClientRepository clientRepository;
    @MockitoBean
    private CloudAccountRepository cloudAccountRepository;
    @MockitoBean
    private TicketReplyRepository ticketReplyRepository;
    @MockitoBean
    private TicketRepository ticketRepository;
    @MockitoBean
    private AdminAccessScope adminAccessScope;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        TicketDto closed = new TicketDto();
        closed.setId(TICKET_ID);
        closed.setStatus("CLOSED");
        when(ticketService.closeTicket(eq(TICKET_ID), anyBoolean())).thenReturn(closed);
    }

    /** A signed-in browser session: no Authorization header, so the @Order(2) session chain handles it. */
    private static RequestPostProcessor sessionUser(String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        ClientUserDetails user = new ClientUserDetails("user", null, authorities, null, 7L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    @ParameterizedTest(name = "{0} can close a ticket")
    @ValueSource(strings = {"ROLE_BILLOPS", "ROLE_XAMOPS"})
    void customerCanClose(String role) throws Exception {
        // Before the fix this was a 403 that never reached the controller.
        MvcResult result = mvc.perform(post(CLOSE).with(sessionUser(role)))
                .andExpect(status().isOk())
                .andReturn();

        verify(ticketService).closeTicket(TICKET_ID, false);
        // The page reads the closed ticket from the response body, so it must not be empty.
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"CLOSED\"");
    }

    @ParameterizedTest(name = "{0} closes as an admin")
    @ValueSource(strings = {"ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB"})
    void adminClosesAsAdmin(String role) throws Exception {
        mvc.perform(post(CLOSE).with(sessionUser(role)))
                .andExpect(status().isOk());

        verify(ticketService).closeTicket(TICKET_ID, true);
    }

    @Test
    void ticketTheCustomerCannotSeeIsNotFound() throws Exception {
        when(ticketService.closeTicket(TICKET_ID, false)).thenThrow(new RuntimeException("Ticket not found"));

        mvc.perform(post(CLOSE).with(sessionUser("ROLE_BILLOPS")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCallerCannotClose() throws Exception {
        mvc.perform(post(CLOSE))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ticketService);
    }

    @Test
    void customerStillCannotReopen() throws Exception {
        mvc.perform(post(REOPEN).with(sessionUser("ROLE_BILLOPS")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ticketService);
    }
}
