package com.xammer.billops.controller;

import com.xammer.billops.config.SecurityConfig;
import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import com.xammer.billops.security.CustomUserDetailsService;
import com.xammer.billops.security.TrialEnforcementFilter;
import com.xammer.billops.service.CloudFrontUsageService;
import com.xammer.billops.service.InvoiceManagementService;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.security.ClientUserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin CloudFront billing API is for BillOps admins and super admins only.
 *
 * <p>Regression guard for audit finding A1. {@code /api/admin/cloudfront/**} sat on the session
 * chain's permitAll list and the controller's {@code @PreAuthorize} was commented out, so anyone
 * could read every client's private CloudFront rates, change or delete them, and generate or
 * rewrite CloudFront invoices without signing in. Now the URL rule requires a signed-in user
 * (anonymous: 401) and the class-level {@code @PreAuthorize} admits only ROLE_BILLOPS_ADMIN and
 * ROLE_SUPER_ADMIN (anyone else: 403, including a "Login As" token for a non-admin user).
 * ROLE_BILLOPS_ADMIN_SUB is excluded on purpose: nothing here is scoped to a sub-admin's clients.
 *
 * <p>CSRF stays disabled, as on every other billops endpoint. Cross-site writes are stopped by the
 * CORS origin allow-list instead, and these tests pin that: a foreign or {@code null} Origin gets
 * 403 before the controller runs, while the app's own origin gets through.
 *
 * <p>Runs the real {@link SecurityConfig} chains through MockMvc with only collaborators mocked.
 * Not {@code @SpringBootTest}/{@code @WebMvcTest}: those would pull in every {@code @Component}
 * and the database behind it. MockMvc cannot perform a container ERROR dispatch, so these tests
 * cannot show that a denied Login-As request keeps its 403 on that dispatch rather than turning
 * into a 401. That was checked on embedded Tomcat when this fix was made, and
 * {@code ImpersonationJwtFilterTest} guards the filter behaviour it relies on.
 */
@SpringJUnitWebConfig(AdminCloudFrontControllerSecurityTest.TestConfig.class)
@TestPropertySource(properties = "app.impersonation.secret-key=" + AdminCloudFrontControllerSecurityTest.SECRET)
class AdminCloudFrontControllerSecurityTest {

    /** Base64 of "test-only-impersonation-signing-key-not-a-secret" (48 bytes; HS256 needs 32+). */
    static final String SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private static final String BASE = "/api/admin/cloudfront";
    private static final String ACCOUNT_ID = "123456789012";

    private static final List<String> ADMIN_ROLES = List.of("ROLE_BILLOPS_ADMIN", "ROLE_SUPER_ADMIN");
    private static final List<String> NON_ADMIN_ROLES =
            List.of("ROLE_XAMOPS", "ROLE_BILLOPS", "ROLE_BILLOPS_ADMIN_SUB");
    /** What a browser sends from another site, and from a sandboxed frame or file: page. */
    private static final List<String> FOREIGN_ORIGINS = List.of("https://evil.example", "null");

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ImpersonationJwtFilter.class, TrialEnforcementFilter.class,
            AdminCloudFrontController.class})
    static class TestConfig {
    }

    /** Every handler method of AdminCloudFrontController, with the status an admin gets. */
    enum Endpoint {
        GET_RATES(200, false, () -> get(BASE + "/rates")),
        GET_CLIENT_RATES(200, false, () -> get(BASE + "/rates/client/7")),
        SAVE_RATE(201, true, () -> post(BASE + "/rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")),
        DELETE_RATE(204, true, () -> delete(BASE + "/rates/1")),
        UPDATE_INVOICE(200, true, () -> put(BASE + "/invoice/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceId\":1,\"lineItems\":[]}")),
        GENERATE_FROM_AWS(200, true, () -> post(BASE + "/generate-from-aws")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + ACCOUNT_ID + "\",\"year\":2026,\"month\":7}")),
        GENERATE_FROM_FILE(200, true, () -> usageUpload("/generate-from-file")),
        GENERATE_FROM_UPLOAD(200, true, () -> usageUpload("/generate-from-upload"));

        final int adminStatus;
        final boolean write;
        private final Supplier<AbstractMockHttpServletRequestBuilder<?>> request;

        Endpoint(int adminStatus, boolean write, Supplier<AbstractMockHttpServletRequestBuilder<?>> request) {
            this.adminStatus = adminStatus;
            this.write = write;
            this.request = request;
        }

        /** A fresh builder on every call, so one test's headers never leak into another. */
        AbstractMockHttpServletRequestBuilder<?> request() {
            return request.get();
        }
    }

    @MockitoBean
    private CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private CloudFrontUsageService cloudFrontUsageService;
    @MockitoBean
    private InvoiceManagementService invoiceManagementService;
    @MockitoBean
    private CloudFrontPrivateRateRepository privateRateRepository;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        when(invoiceManagementService.generateCloudFrontInvoice(any(), anyInt(), anyInt(), any()))
                .thenReturn(new Invoice());
        when(invoiceManagementService.updateInvoiceLineItems(any(), any())).thenReturn(new Invoice());
        when(privateRateRepository.existsById(1L)).thenReturn(true);
    }

    static Stream<Endpoint> endpoints() {
        return Arrays.stream(Endpoint.values());
    }

    static Stream<Arguments> endpointsForNonAdminRoles() {
        return combine(endpoints(), NON_ADMIN_ROLES);
    }

    static Stream<Arguments> endpointsForAdminRoles() {
        return combine(endpoints(), ADMIN_ROLES);
    }

    static Stream<Arguments> writeEndpointsForForeignOrigins() {
        return combine(endpoints().filter(endpoint -> endpoint.write), FOREIGN_ORIGINS);
    }

    private static Stream<Arguments> combine(Stream<Endpoint> endpoints, List<String> values) {
        return endpoints.flatMap(endpoint -> values.stream().map(value -> Arguments.of(endpoint, value)));
    }

    private static AbstractMockHttpServletRequestBuilder<?> usageUpload(String path) {
        return multipart(BASE + path)
                .file(new MockMultipartFile("file", "usage.csv", "text/csv",
                        "lineItem/ProductCode\nAmazonCloudFront\n".getBytes(StandardCharsets.UTF_8)))
                .param("accountId", ACCOUNT_ID)
                .param("year", "2026")
                .param("month", "7");
    }

    /** A signed-in browser session: no Authorization header, so the @Order(2) session chain handles it. */
    private static RequestPostProcessor sessionUser(String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        ClientUserDetails user = new ClientUserDetails("user", null, authorities, null, 7L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    /** Minted the way ImpersonationTokenController.buildToken does it, for a target user with this role. */
    private static String loginAsToken(String role) {
        return Jwts.builder()
                .setSubject("42")
                .claim("role", role)
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), SignatureAlgorithm.HS256)
                .compact();
    }

    private void assertControllerNotReached() {
        verifyNoInteractions(cloudFrontUsageService, invoiceManagementService, privateRateRepository);
    }

    private void assertControllerReached() {
        int calls = mockingDetails(cloudFrontUsageService).getInvocations().size()
                + mockingDetails(invoiceManagementService).getInvocations().size()
                + mockingDetails(privateRateRepository).getInvocations().size();
        assertThat(calls).as("calls from the controller to its collaborators").isPositive();
    }

    @ParameterizedTest(name = "anonymous {0} -> 401")
    @MethodSource("endpoints")
    void anonymousCallerIsUnauthorized(Endpoint endpoint) throws Exception {
        // Before the fix every one of these ran the handler for anyone on the internet.
        mvc.perform(endpoint.request())
                .andExpect(status().isUnauthorized());

        assertControllerNotReached();
    }

    @ParameterizedTest(name = "session {1} {0} -> 403")
    @MethodSource("endpointsForNonAdminRoles")
    void signedInNonAdminIsForbidden(Endpoint endpoint, String role) throws Exception {
        mvc.perform(endpoint.request().with(sessionUser(role)))
                .andExpect(status().isForbidden());

        assertControllerNotReached();
    }

    @ParameterizedTest(name = "Login-As {1} {0} -> 403")
    @MethodSource("endpointsForNonAdminRoles")
    void loginAsNonAdminIsForbidden(Endpoint endpoint, String role) throws Exception {
        MvcResult result = mvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAsToken(role)))
                .andExpect(status().isForbidden())
                .andReturn();

        assertControllerNotReached();
        // The impersonated tab must never be given (or overwrite) a JSESSIONID.
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @ParameterizedTest(name = "session {1} {0} reaches the controller")
    @MethodSource("endpointsForAdminRoles")
    void adminReachesTheController(Endpoint endpoint, String role) throws Exception {
        mvc.perform(endpoint.request().with(sessionUser(role)))
                .andExpect(status().is(endpoint.adminStatus));

        assertControllerReached();
    }

    @Test
    void loginAsBillopsAdminActsAsThatAdmin() throws Exception {
        mvc.perform(Endpoint.GET_RATES.request()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAsToken("ROLE_BILLOPS_ADMIN")))
                .andExpect(status().isOk());

        verify(privateRateRepository).findAll();
    }

    @ParameterizedTest(name = "admin {0} with Origin {1} -> 403")
    @MethodSource("writeEndpointsForForeignOrigins")
    void crossOriginWriteIsRejectedBeforeTheController(Endpoint endpoint, String origin) throws Exception {
        mvc.perform(endpoint.request()
                        .with(sessionUser("ROLE_BILLOPS_ADMIN"))
                        .header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));

        assertControllerNotReached();
    }

    @Test
    void uploadFromTheAppsOwnOriginIsAllowed() throws Exception {
        mvc.perform(Endpoint.GENERATE_FROM_FILE.request()
                        .with(sessionUser("ROLE_BILLOPS_ADMIN"))
                        .header(HttpHeaders.ORIGIN, "https://live.xamops.com"))
                .andExpect(status().isOk());

        verify(invoiceManagementService).generateCloudFrontInvoice(eq(ACCOUNT_ID), eq(2026), eq(7), any());
    }

    @Test
    void invalidLoginAsTokenIsRejected() throws Exception {
        mvc.perform(Endpoint.GET_RATES.request()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"message\":\"Invalid or expired impersonation token\"}"));

        assertControllerNotReached();
    }
}
