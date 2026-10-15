package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.security.ResourceSecurityFindings;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ProductError;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityFinding;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityGroup;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.SecurityDeepLink;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.ResourceIdResolver;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurInsightCache;
import com.xammer.cloud.service.cur.CurSourceResolver;
import com.xammer.cloud.service.security.ResourceSecurityAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code /resource-security} endpoint's duties: never answer another tenant's findings, never
 * 500 on a malformed request, never 404 for a resource that merely has nothing to show, and never
 * let one product's failure take the whole response down.
 *
 * <p>Mirrors {@code CurInsightsControllerTest}: a pass-through {@link CurInsightCache} stub so the
 * supplier runs, and {@code allow()}/{@code deny()} entitlement helpers. The aggregation service is
 * mocked — its own behaviour is unit-tested separately; here we pin the controller's prologue and
 * wiring.
 */
@ExtendWith(MockitoExtension.class)
class ResourceSecurityControllerTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String RESOURCE_ID = "arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private CurSourceResolver curSourceResolver;
    @Mock private CurAdvancedMetricsService metricsService;
    @Mock private CurInsightCache insightCache;
    @Mock private CloudAccountAccessService accessService;
    @Mock private ResourceIdResolver idResolver;
    @Mock private ResourceSecurityAggregationService aggregator;

    private CurInsightsController controller;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        controller = new CurInsightsController(cloudAccountRepository, curSourceResolver,
                metricsService, insightCache, accessService, idResolver, aggregator,
                List.of("cost-center"));

        Client client = new Client();
        client.setId(7L);
        account = new CloudAccount();
        account.setId(42L);
        account.setAwsAccountId(ACCOUNT_ID);
        account.setProvider("AWS");
        account.setClient(client);

        lenient().when(insightCache.getOrCompute(anyString(), any()))
                .thenAnswer(inv -> inv.<Supplier<ResponseEntity<?>>>getArgument(1).get());

        signIn();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A known resource with findings is a 200 carrying the normalised body")
    void aKnownResourceReturnsNormalisedBody() {
        allow();
        ResourceSecurityFindings answer = new ResourceSecurityFindings();
        answer.setResourceId(RESOURCE_ID);
        answer.setMatched(true);
        answer.setGroups(List.of(new ResourceSecurityGroup(
                "SECURITY_CENTER", "Security Center", "/security",
                List.of(new ResourceSecurityFinding("Public S3 bucket", "CRITICAL", null,
                        new SecurityDeepLink("/security", Map.of("resource", RESOURCE_ID)))))));
        when(aggregator.aggregate(eq(ACCOUNT_ID), eq(RESOURCE_ID), any()))
                .thenReturn(ok(answer));

        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID, RESOURCE_ID, null, null);

        assertEquals(200, response.getStatusCode().value());
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();
        assertTrue(body != null && body.isMatched());
        assertEquals(1, body.getGroups().size());
        assertEquals("CRITICAL", body.getGroups().get(0).getFindings().get(0).getSeverity());
    }

    @Test
    @DisplayName("A resource with nothing to show is a 200 with empty groups and errors, not a 404")
    void anUnknownResourceIsAnEmptyAnswerRatherThanAFailure() {
        allow();
        ResourceSecurityFindings answer = new ResourceSecurityFindings();
        answer.setResourceId(RESOURCE_ID);
        answer.setMatched(false);
        when(aggregator.aggregate(eq(ACCOUNT_ID), eq(RESOURCE_ID), any()))
                .thenReturn(ok(answer));

        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID, RESOURCE_ID, null, null);

        assertEquals(200, response.getStatusCode().value());
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();
        assertTrue(body != null && body.getGroups().isEmpty() && body.getErrors().isEmpty());
        assertFalse(body.isMatched());
    }

    @Test
    @DisplayName("One product failing is a populated errors[] alongside groups[], still a 200")
    void oneFailingProductDoesNotFiveHundred() {
        allow();
        ResourceSecurityFindings answer = new ResourceSecurityFindings();
        answer.setResourceId(RESOURCE_ID);
        answer.setMatched(true);
        answer.setGroups(List.of(new ResourceSecurityGroup(
                "SECURITY_CENTER", "Security Center", "/security",
                List.of(new ResourceSecurityFinding("Public S3 bucket", "CRITICAL", null,
                        new SecurityDeepLink("/security", Map.of("resource", RESOURCE_ID)))))));
        answer.setErrors(List.of(new ProductError("PERFORMANCE",
                "Performance insights could not be read.")));
        when(aggregator.aggregate(eq(ACCOUNT_ID), eq(RESOURCE_ID), any()))
                .thenReturn(ok(answer));

        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID, RESOURCE_ID, null, null);

        assertEquals(200, response.getStatusCode().value());
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();
        assertTrue(body != null && !body.getGroups().isEmpty() && !body.getErrors().isEmpty());
        assertEquals("PERFORMANCE", body.getErrors().get(0).getProduct());
    }

    @Test
    @DisplayName("An account the caller does not hold answers 404, before the cache or aggregator")
    void anUnentitledAccountIsNotFound() {
        deny();

        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID, RESOURCE_ID, null, null);

        assertEquals(404, response.getStatusCode().value());
        assertNull(response.getBody());
        // Entitlement is checked before the cache and the aggregator both.
        verifyNoInteractions(insightCache, aggregator);
    }

    @Test
    @DisplayName("A blank resource id is a 400 before entitlement, touching nothing downstream")
    void aBlankResourceIdIsRefused() {
        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID, "  ", null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("A resource id is required", body(response).get("error"));
        verifyNoInteractions(insightCache, aggregator, accessService);
    }

    @Test
    @DisplayName("A resource id past the character bound is a 400, not an unbounded lookup")
    void anOverlongResourceIdIsRefused() {
        allow();

        ResponseEntity<?> response = controller.resourceSecurity(ACCOUNT_ID,
                "x".repeat(ResourceSecurityAggregationService.MAX_RESOURCE_ID_CHARS + 1), null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Resource id too long", body(response).get("error"));
        verifyNoInteractions(insightCache, aggregator);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void allow() {
        lenient().when(accessService.requireAwsAccess(any(), eq(ACCOUNT_ID))).thenReturn(account);
    }

    private void deny() {
        when(accessService.requireAwsAccess(any(), anyString()))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));
    }

    private void signIn() {
        ClientUserDetails caller = new ClientUserDetails("reader", "secret",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 7L, 11L, "tenant");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, caller.getAuthorities()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    /**
     * {@code ResponseEntity.ok(body)} typed as {@code ResponseEntity<Object>} — the aggregator's
     * declared return type — so the Mockito stub's {@code thenReturn} matches it exactly.
     */
    private ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok(body);
    }
}
