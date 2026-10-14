package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurInsightCache;
import com.xammer.cloud.service.cur.CurSourceResolver;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CUR insights endpoints' duties towards their callers: never answer another tenant's invoice,
 * never answer a malformed request with a 500, and never conflate "no report" with "this report
 * cannot answer that".
 *
 * <p>The cache is a pass-through mock. A real {@link CurInsightCache} would need four more
 * collaborators and could not be verified, while a bare mock returns null from
 * {@code getOrCompute} and would break every test here — so the stub runs the supplier and the
 * deny tests can still assert the cache was never reached.
 */
@ExtendWith(MockitoExtension.class)
class CurInsightsControllerTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private CurSourceResolver curSourceResolver;
    @Mock private CurAdvancedMetricsService metricsService;
    @Mock private CurInsightCache insightCache;
    @Mock private CloudAccountAccessService accessService;

    private CurInsightsController controller;
    private CloudAccount account;
    private CurSource source;

    @BeforeEach
    void setUp() {
        controller = new CurInsightsController(cloudAccountRepository, curSourceResolver,
                metricsService, insightCache, accessService, List.of("cost-center"));

        Client client = new Client();
        client.setId(7L);
        account = new CloudAccount();
        account.setId(42L);
        account.setAwsAccountId(ACCOUNT_ID);
        account.setProvider("AWS");
        account.setClient(client);

        source = new CurSource();
        source.setId(1L);
        source.setAthenaDatabase("xamops_cur");
        source.setAthenaTable("xamops_cur");
        source.setCurVersion("cur");
        source.setFormat("Parquet");

        // lenient() because the deny tests assert the cache is never reached at all, and
        // MockitoExtension is strict about unused stubs.
        lenient().when(insightCache.getOrCompute(anyString(), any()))
                .thenAnswer(inv -> inv.<Supplier<ResponseEntity<?>>>getArgument(1).get());

        signIn();
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ── Entitlement ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An account the caller does not hold answers exactly as an unknown one does")
    void anotherTenantsAccountIsIndistinguishableFromAnUnknownOne() {
        deny();

        ResponseEntity<?> charges = controller.chargesByService(ACCOUNT_ID, "2026-08", null);
        ResponseEntity<?> chargeTypes = controller.chargeTypes(ACCOUNT_ID, "2026-08", null);
        ResponseEntity<?> resources = controller.serviceResources(ACCOUNT_ID, "AWS Lambda",
                "2026-08", null);
        ResponseEntity<?> capabilities = controller.capabilities(ACCOUNT_ID, "2026-08");
        ResponseEntity<?> priceVsVolume = controller.priceVsVolume(ACCOUNT_ID, "2026-08");
        ResponseEntity<?> change = controller.resourceChange(ACCOUNT_ID, "i-0abc", null, null,
                "2026-08");

        for (ResponseEntity<?> response : List.of(charges, chargeTypes, resources, capabilities,
                priceVsVolume, change)) {
            // 404 with an empty body, byte-identical to an unknown account id, so the endpoint
            // cannot be used to discover which account ids exist.
            assertEquals(404, response.getStatusCode().value());
            assertNull(response.getBody());
        }
        verifyNoInteractions(metricsService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"charges-by-service", "service-resources", "capabilities",
            "price-vs-volume", "resource-change"})
    @DisplayName("The entitlement check runs before the cache, not inside its compute supplier")
    void entitlementIsCheckedBeforeTheCache(String endpoint) {
        /*
         * This is the test that matters. A check inside the compute supplier would pass the test
         * above and still serve another tenant's cached invoice on the second request, because
         * getOrCompute returns the stored json without calling the supplier and the key carries no
         * client id. It would also run on awsTaskExecutor during a background refresh, where there
         * is no security context — failing closed on a legitimate refresh and evicting a good entry
         * through refreshInBackground's is4xxClientError() branch.
         */
        deny();

        ResponseEntity<?> response = switch (endpoint) {
            case "charges-by-service" -> controller.chargesByService(ACCOUNT_ID, null, null);
            case "service-resources" -> controller.serviceResources(ACCOUNT_ID, "AWS Lambda",
                    null, null);
            case "capabilities" -> controller.capabilities(ACCOUNT_ID, null);
            case "price-vs-volume" -> controller.priceVsVolume(ACCOUNT_ID, null);
            default -> controller.resourceChange(ACCOUNT_ID, "i-0abc", null, null, null);
        };

        assertEquals(404, response.getStatusCode().value());
        verifyNoInteractions(insightCache);
    }

    @Test
    @DisplayName("No principal at all answers 404, not a 500")
    void aMissingPrincipalFailsClosed() {
        SecurityContextHolder.clearContext();
        // requireAwsAccess rejects a null caller by design; this pins that the controller hands it
        // a null rather than dereferencing one first.
        when(accessService.requireAwsAccess(eq(null), anyString()))
                .thenThrow(new AccessDeniedException("Authentication required."));

        assertEquals(404, controller.capabilities(ACCOUNT_ID, null).getStatusCode().value());
    }

    // ── Period and basis parsing ────────────────────────────────────────────────

    @ParameterizedTest(name = "period={0}")
    @ValueSource(strings = {"2026-13", ""})
    @DisplayName("An unusable period is a 400 on every endpoint that takes one")
    void anUnusablePeriodIsAlwaysFourHundred(String period) {
        // Regression: runWithSource and computePriceVsVolume both parsed outside their try, so a
        // bad month was an uncaught DateTimeParseException and a 500 on eleven endpoints. The empty
        // string threw the same way.
        allow();

        List<ResponseEntity<?>> responses = List.of(
                controller.chargesByService(ACCOUNT_ID, period, null),
                controller.chargeTypes(ACCOUNT_ID, period, null),
                controller.serviceResources(ACCOUNT_ID, "AWS Lambda", period, null),
                controller.capabilities(ACCOUNT_ID, period),
                controller.priceVsVolume(ACCOUNT_ID, period),
                controller.resourceChange(ACCOUNT_ID, "i-0abc", null, null, period));

        for (ResponseEntity<?> response : responses) {
            assertEquals(400, response.getStatusCode().value());
            assertEquals("Invalid period", body(response).get("error"));
            assertTrue(String.valueOf(body(response).get("detail")).contains("Expected YYYY-MM"));
        }
        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("A misspelled basis is a 400 listing both values, and no query is issued")
    void aMisspelledBasisIsRefused() {
        allow();

        ResponseEntity<?> response = controller.chargesByService(ACCOUNT_ID, "2026-08", "amortised");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid cost basis", body(response).get("error"));
        assertTrue(String.valueOf(body(response).get("detail"))
                .contains("Expected 'unblended' or 'amortized', got 'amortised'"));
        // Answered on the unblended basis, it would be a figure labelled as something it is not.
        verifyNoInteractions(metricsService);
    }

    // ── Endpoint parameters ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A blank service names the parameter and points at the account-wide endpoint")
    void serviceResourcesRequiresAService() {
        allow();

        ResponseEntity<?> response = controller.serviceResources(ACCOUNT_ID, " ", "2026-08", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("A service is required", body(response).get("error"));
        assertTrue(String.valueOf(body(response).get("detail")).contains("/top-resources"));
    }

    @Test
    @DisplayName("An abusively long service name is bounded before it reaches the SQL builder")
    void serviceResourcesBoundsTheServiceName() {
        allow();

        ResponseEntity<?> response =
                controller.serviceResources(ACCOUNT_ID, "x".repeat(300), "2026-08", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Service name too long", body(response).get("error"));
    }

    @Test
    @DisplayName("A service absent from the report is a 200 with nothing in it, not a 404")
    void anAbsentServiceIsAnEmptyAnswerRatherThanAFailure() {
        allow();
        resolves();
        when(metricsService.getServiceResources(any(), any(), eq("AWS Snowball"), any(), any(),
                eq(CostBasis.UNBLENDED)))
                .thenReturn(new CurAdvancedMetricsService.TopResources(List.of(), 0, 0));

        ResponseEntity<?> response =
                controller.serviceResources(ACCOUNT_ID, "AWS Snowball", "2026-08", null);

        // The question was well-formed and the report answered it. 404 would say the opposite.
        assertEquals(200, response.getStatusCode().value());
    }

    // ── Source resolution and failure mapping ───────────────────────────────────

    @Test
    @DisplayName("An unknown account id is a 404 on every new endpoint")
    void anUnknownAccountIsNotFound() {
        allow();
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of());

        assertEquals(404, controller.capabilities(ACCOUNT_ID, "2026-08").getStatusCode().value());
        assertEquals(404, controller.serviceResources(ACCOUNT_ID, "AWS Lambda", "2026-08", null)
                .getStatusCode().value());
    }

    @Test
    @DisplayName("A 409 carries the resolver's own sentence, not the generic one")
    void noSourceExplainsTheActualLimit() {
        // Regression: runWithSource called the no-reason form, so ten endpoints told a reader to
        // connect a report that was already connected rather than naming the real limit.
        allow();
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
        when(curSourceResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(curSourceResolver.describeCoverage(any(), any(), any(), anyBoolean()))
                .thenReturn(new CurSourceResolver.Coverage(Optional.empty(), "STARTS_LATER",
                        "This report starts at 2026-09.", "2026-09", null));

        ResponseEntity<?> charges = controller.chargesByService(ACCOUNT_ID, "2026-08", null);
        ResponseEntity<?> priceVsVolume = controller.priceVsVolume(ACCOUNT_ID, "2026-08");

        for (ResponseEntity<?> response : List.of(charges, priceVsVolume)) {
            assertEquals(409, response.getStatusCode().value());
            assertEquals("This report starts at 2026-09.", body(response).get("detail"));
        }
    }

    @Test
    @DisplayName("A report that cannot answer the amortized basis is 422, naming the columns")
    void aReportThatCannotAnswerIsFourTwentyTwo() {
        allow();
        resolves();
        when(metricsService.getChargesByService(any(), any(), any(), any(), eq(CostBasis.AMORTIZED)))
                .thenThrow(new ReportCannotAnswerException("the amortized cost basis",
                        List.of("reservation_effective_cost")));

        ResponseEntity<?> response = controller.chargesByService(ACCOUNT_ID, "2026-08", "amortized");

        // Not 502 — nothing failed. Not 409 — the client hides the whole card on that, and this is
        // a readable report that cannot answer one question. Not a 200 of unblended figures.
        assertEquals(422, response.getStatusCode().value());
        assertEquals("This report cannot answer the amortized cost basis",
                body(response).get("error"));
        assertEquals(List.of("reservation_effective_cost"), body(response).get("missingColumns"));
    }

    @Test
    @DisplayName("An Athena failure is a 502, which is a different fact from a 422")
    void anAthenaFailureIsFiveOhTwo() {
        allow();
        resolves();
        when(metricsService.getReportCapabilities(any(), any()))
                .thenThrow(new RuntimeException("GENERIC_INTERNAL_ERROR"));

        ResponseEntity<?> response = controller.capabilities(ACCOUNT_ID, "2026-08");

        assertEquals(502, response.getStatusCode().value());
        assertEquals("Could not read the Cost and Usage Report", body(response).get("error"));
    }

    // ── /capabilities ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Capabilities answer 200 with a false flag, never a 409")
    void capabilitiesReportALimitationRatherThanRefusing() {
        allow();
        resolves();
        when(metricsService.getReportCapabilities(any(), any()))
                .thenReturn(new CurAdvancedMetricsService.ReportCapabilities(true, List.of(), false,
                        "", "This export does not carry pricing_unit, so quantities are shown"
                        + " without a unit."));

        ResponseEntity<?> response = controller.capabilities(ACCOUNT_ID, "2026-08");

        assertEquals(200, response.getStatusCode().value());
        CurAdvancedMetricsService.ReportCapabilities capabilities =
                (CurAdvancedMetricsService.ReportCapabilities) response.getBody();
        assertTrue(capabilities != null && !capabilities.unitAvailable());
    }

    @Test
    @DisplayName("Capabilities bypass the insight cache, while charges-by-service does not")
    void capabilitiesDoNotGoThroughTheInsightCache() {
        /*
         * Pins a bypass that is otherwise invisible and would be tidied back into withSource by the
         * next reader. The answer already comes from the column catalog's own cache (6h on success,
         * 10 min on failure, zero bytes either way); a 1h/6h HTTP entry with stale-while-revalidate
         * on top would keep the basis toggle disabled for hours after the catalog recovered.
         */
        allow();
        resolves();
        when(metricsService.getReportCapabilities(any(), any()))
                .thenReturn(new CurAdvancedMetricsService.ReportCapabilities(true, List.of(), true,
                        "", ""));

        controller.capabilities(ACCOUNT_ID, "2026-08");
        verifyNoInteractions(insightCache);

        when(metricsService.getChargesByService(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        controller.chargesByService(ACCOUNT_ID, "2026-08", null);
        verify(insightCache).getOrCompute(eq(ACCOUNT_ID), any());
    }

    // ── The month's end ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("The current month runs to its end, not to today — $1,107 capped vs $2,367 not")
    void theCurrentMonthIsNotCappedAtToday() {
        /*
         * Capping looks right and is wrong: reservation and Savings Plan fees, support and
         * subscriptions carry usage dates spanning the whole billing period from the day it opens,
         * and they are already on the bill. Measured on a live report for September: $1,107 capped
         * against $2,367 uncapped, a $1,260 difference of period-spanning rows.
         */
        allow();
        resolves();
        when(metricsService.getChargesByService(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        YearMonth now = YearMonth.now();
        controller.chargesByService(ACCOUNT_ID, now.toString(), null);

        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(metricsService).getChargesByService(any(), any(), dates.capture(), dates.capture(),
                any());
        assertEquals(now.atDay(1), dates.getAllValues().get(0));
        assertEquals(now.atEndOfMonth(), dates.getAllValues().get(1));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** A caller holding the account under test. */
    private void allow() {
        lenient().when(accessService.requireAwsAccess(any(), eq(ACCOUNT_ID))).thenReturn(account);
    }

    /** A caller who does not. */
    private void deny() {
        when(accessService.requireAwsAccess(any(), anyString()))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));
    }

    /** The account exists and a report covers the requested month. */
    private void resolves() {
        lenient().when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID))
                .thenReturn(List.of(account));
        lenient().when(curSourceResolver.resolve(any(), any(), any()))
                .thenReturn(Optional.of(source));
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
}
