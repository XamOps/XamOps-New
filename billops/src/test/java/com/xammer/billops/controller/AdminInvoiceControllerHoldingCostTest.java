package com.xammer.billops.controller;

import com.xammer.billops.config.SecurityConfig;
import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import com.xammer.billops.security.CustomUserDetailsService;
import com.xammer.billops.security.TrialEnforcementFilter;
import com.xammer.billops.service.InvoiceManagementService;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code PUT /api/admin/invoices/{id}/holding-cost} endpoint: 200 with the recomputed
 * invoice for a valid percent, 400 for an out-of-range percent, 409 for a non-draft invoice,
 * and 403 for a caller lacking ROLE_BILLOPS_ADMIN / ROLE_SUPER_ADMIN.
 *
 * <p>Runs the real {@link SecurityConfig} chains through MockMvc with only collaborators
 * mocked, mirroring {@link AdminCloudFrontControllerSecurityTest}. Writes carry the app's own
 * Origin so the CORS allow-list (enforced before the controller) lets them through.
 */
@SpringJUnitWebConfig(AdminInvoiceControllerHoldingCostTest.TestConfig.class)
@TestPropertySource(properties = "app.impersonation.secret-key=" + AdminInvoiceControllerHoldingCostTest.SECRET)
class AdminInvoiceControllerHoldingCostTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final String URL = "/api/admin/invoices/1/holding-cost";
    private static final String ORIGIN = "https://live.xamops.com";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            AdminInvoiceController.class})
    static class TestConfig {
    }

    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private InvoiceManagementService invoiceManagementService;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static RequestPostProcessor sessionUser(String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        ClientUserDetails user = new ClientUserDetails("user", null, authorities, null, 7L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        return put(URL)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void validPercentReturns200WithRecomputedBody() throws Exception {
        Invoice saved = new Invoice();
        saved.setId(1L);
        saved.setMarketplaceHoldingCostPercent(new BigDecimal("15.00"));
        when(invoiceManagementService.setMarketplaceHoldingCost(eq(1L), any())).thenReturn(saved);

        mvc.perform(request("{\"percent\":15}").with(sessionUser("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.marketplaceHoldingCostPercent").value(15.00));

        verify(invoiceManagementService).setMarketplaceHoldingCost(eq(1L), any());
    }

    @Test
    void outOfRangePercentReturns400WithMessage() throws Exception {
        when(invoiceManagementService.setMarketplaceHoldingCost(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("Holding cost must be between 0 and 100 percent."));

        mvc.perform(request("{\"percent\":101}").with(sessionUser("ROLE_SUPER_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Holding cost must be between 0 and 100 percent."));
    }

    @Test
    void nonDraftReturns409WithMessage() throws Exception {
        when(invoiceManagementService.setMarketplaceHoldingCost(eq(1L), any()))
                .thenThrow(new IllegalStateException("Cannot change the holding cost on a finalized invoice."));

        mvc.perform(request("{\"percent\":15}").with(sessionUser("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot change the holding cost on a finalized invoice."));
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(request("{\"percent\":15}").with(sessionUser("ROLE_BILLOPS")))
                .andExpect(status().isForbidden());

        verify(invoiceManagementService, never()).setMarketplaceHoldingCost(any(), any());
    }
}
