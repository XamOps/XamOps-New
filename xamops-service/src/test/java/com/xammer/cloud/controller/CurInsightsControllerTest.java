package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.ResourceIdResolver;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAdvancedMetricsService;
import com.xammer.cloud.service.cur.CurInsightCache;
import com.xammer.cloud.service.cur.CurSourceResolver;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import com.xammer.cloud.service.cur.ResourceCostJoin;
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
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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
    @Mock private ResourceIdResolver idResolver;
    @Mock private com.xammer.cloud.service.security.ResourceSecurityAggregationService resourceSecurityAggregationService;

    private CurInsightsController controller;
    private CloudAccount account;
    private CurSource source;

    @BeforeEach
    void setUp() {
        controller = new CurInsightsController(cloudAccountRepository, curSourceResolver,
                metricsService, insightCache, accessService, idResolver,
                resourceSecurityAggregationService, List.of("cost-center"));

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
        // A separate stub for the keyed overload, which /resource-costs uses. The two-argument one
        // above is left exactly as it was, because withSource still calls it for the other ten
        // endpoints — that is the whole reason the overload is a sibling rather than a refactor.
        lenient().when(insightCache.getOrCompute(anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.<Supplier<ResponseEntity<?>>>getArgument(2).get());

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

    // ── /resource-identities and /resource-costs ────────────────────────────────

    @Test
    @DisplayName("Both per-resource-set endpoints answer 404 for an account the caller does not hold")
    void theResourceSetEndpointsAreEntitled() {
        deny();

        ResponseEntity<?> identities = controller.resourceIdentities(ACCOUNT_ID, "i-0abc");
        ResponseEntity<?> costs = controller.resourceCosts(ACCOUNT_ID, "i-0abc", "2026-08", null);

        for (ResponseEntity<?> response : List.of(identities, costs)) {
            assertEquals(404, response.getStatusCode().value());
            assertNull(response.getBody());
        }
        // Before any ids validation that names account data, and without consulting the cache.
        verifyNoInteractions(insightCache, idResolver, metricsService);
    }

    @ParameterizedTest(name = "ids={0}")
    @ValueSource(strings = {"", " ", ",,,"})
    @DisplayName("An ids parameter carrying no identifier is a 400 saying so")
    void anEmptyIdListIsRefused(String ids) {
        allow();

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, ids, "2026-08", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("An id is required", body(response).get("error"));
        verifyNoInteractions(idResolver, metricsService);
    }

    @Test
    @DisplayName("An absent ids parameter is a 400, not a request for the whole account")
    void anAbsentIdListIsRefused() {
        allow();

        assertEquals(400, controller.resourceIdentities(ACCOUNT_ID, null).getStatusCode().value());
    }

    @Test
    @DisplayName("The character limit is measured on the raw parameter, before splitting")
    void theCharacterLimitIsMeasuredBeforeCleaning() {
        /*
         * The case that decides between two readings of the same rule. A 3,100-character parameter
         * whose ids dedupe down to 2,900 characters is a 400 under "measure the parameter" and a
         * 200 under "measure what survives cleaning". It is the first: that is the budget the URI
         * actually spends, and it is the only measurement the client can make before it sends.
         */
        allow();
        String duplicated = ("i-" + "0".repeat(98) + ",").repeat(31);
        assertTrue(duplicated.length() > CurAdvancedMetricsService.MAX_IDS_CHARS);

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, duplicated, null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Too many characters of ids", body(response).get("error"));
        assertTrue(String.valueOf(body(response).get("detail"))
                .contains(String.valueOf(CurAdvancedMetricsService.MAX_IDS_CHARS)));
        assertTrue(String.valueOf(body(response).get("detail"))
                .contains(String.valueOf(duplicated.length())));
    }

    @Test
    @DisplayName("Too many ids names the count, not the character limit")
    void theCountLimitIsNamedWhenItIsTheOneBroken() {
        allow();
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < CurAdvancedMetricsService.MAX_LOOKUP_IDS + 20; i++) {
            many.add("i-" + i);
        }
        String ids = String.join(",", many);
        assertTrue(ids.length() < CurAdvancedMetricsService.MAX_IDS_CHARS);

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, ids, null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Too many ids", body(response).get("error"));
        assertTrue(String.valueOf(body(response).get("detail")).contains(String.valueOf(many.size())));
    }

    @Test
    @DisplayName("A single id longer than the inventory's widest identifier column is refused")
    void anOverlongIdIsRefused() {
        allow();

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID,
                "x".repeat(CurAdvancedMetricsService.MAX_ID_CHARS + 1), null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("An id is too long", body(response).get("error"));
    }

    @Test
    @DisplayName("/resource-identities never caches and never resolves a CUR source")
    void identitiesBypassTheCacheAndTheSource() {
        /*
         * Both halves matter. The answer is one indexed read, and the cache's 6h TTL plus
         * stale-while-revalidate would hold an inventory answer past an inventory sync — silently
         * rooting the connectivity tab on a resource that no longer exists. And this endpoint says
         * which resource an id names, which is true whether or not a report covers the month, so a
         * 409 here would be an answer to a question nobody asked.
         */
        allow();
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
        when(idResolver.curIdentifiers(eq(42L), any())).thenReturn(
                new ResourceIdResolver.CurIdentifiers(
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of("i-0abc", new ResourceIdResolver.CanonicalResource(
                                "i-0abc", "EC2 Instance", "prod-web-01", "ap-south-1",
                                "arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc")),
                        Set.of()));

        ResponseEntity<?> response = controller.resourceIdentities(ACCOUNT_ID, "i-0abc");

        assertEquals(200, response.getStatusCode().value());
        ResourceCostJoin.ResourceIdentities identities =
                (ResourceCostJoin.ResourceIdentities) response.getBody();
        assertTrue(identities != null && identities.resolved() == 1);
        assertEquals("i-0abc", identities.identities().get(0).requestedId());
        verifyNoInteractions(insightCache, curSourceResolver, metricsService);
    }

    @Test
    @DisplayName("An unreadable inventory is a 502, not a flow of silently blank costs")
    void anUnreadableInventoryFails() {
        // The tempting fallback — carry on with exact-equality candidates only — blanks every peer
        // whose CUR id is an ARN and produces a flow that looks complete and under-reports.
        allow();
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
        when(idResolver.curIdentifiers(anyLong(), any()))
                .thenThrow(new RuntimeException("connection pool exhausted"));

        ResponseEntity<?> response = controller.resourceIdentities(ACCOUNT_ID, "i-0abc");

        assertEquals(502, response.getStatusCode().value());
        assertEquals("Could not read the resource inventory", body(response).get("error"));
    }

    @Test
    @DisplayName("Every id unanswerable answers 200 without asking Athena anything")
    void nothingUsableSkipsAthena() {
        // `IN ()` is not valid SQL and there is no question to put, so the guard belongs in the
        // handler rather than being defended against inside the metrics service.
        allow();
        resolves();
        when(idResolver.curIdentifiers(eq(42L), any())).thenReturn(
                new ResourceIdResolver.CurIdentifiers(
                        Map.of("shared", List.of()),
                        Map.of("shared", List.of("shared")),
                        Map.of(),
                        Set.of("shared")));

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, "shared", "2026-08", null);

        assertEquals(200, response.getStatusCode().value());
        ResourceCostJoin.ResourceCostsResponse costs =
                (ResourceCostJoin.ResourceCostsResponse) response.getBody();
        assertTrue(costs != null && costs.costs().isEmpty());
        assertEquals(List.of("shared"), costs.ambiguous());
        assertEquals(0, costs.pricedCount());
        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("/resource-costs prices through the keyed cache, on a bounded key")
    void costsUseTheKeyedCacheOverload() {
        allow();
        resolves();
        when(idResolver.curIdentifiers(eq(42L), any())).thenReturn(
                new ResourceIdResolver.CurIdentifiers(
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of(),
                        Set.of()));
        when(metricsService.getResourceCosts(any(), any(), any(), any(), any(),
                eq(CostBasis.UNBLENDED)))
                .thenReturn(new CurAdvancedMetricsService.ResourceGroupCosts(
                        List.of(new CurAdvancedMetricsService.ResourceGroupCost("i-0abc", 1621.35)),
                        1621.35, 1));

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, "i-0abc", "2026-08", null);

        assertEquals(200, response.getStatusCode().value());
        ResourceCostJoin.ResourceCostsResponse costs =
                (ResourceCostJoin.ResourceCostsResponse) response.getBody();
        assertEquals(1, costs.pricedCount());
        assertEquals(1621.35, costs.costs().get(0).cost(), 0.0001);

        ArgumentCaptor<String> suffix = ArgumentCaptor.forClass(String.class);
        verify(insightCache).getOrCompute(eq(ACCOUNT_ID), suffix.capture(), any());
        // period and basis readable so CurInsightCache.isOpenMonth still finds the month, the ids
        // hashed so the key cannot outgrow what cached_data.cache_key holds.
        assertTrue(suffix.getValue().contains("period=2026-08"), suffix.getValue());
        assertTrue(suffix.getValue().contains("basis=unblended"), suffix.getValue());
        assertTrue(suffix.getValue().matches(".*&ids=[0-9a-f]{64}$"), suffix.getValue());
    }

    @Test
    @DisplayName("The cost cache key is bounded and order-independent")
    void theCostCacheKeyIsBoundedAndOrderIndependent() {
        /*
         * Asserted rather than trusted to arithmetic, because the failure it guards is silent: a
         * key past what cached_data.cache_key holds still serves a correct 200 out of Redis while
         * the durable tier stops persisting, so the only symptom is Athena being re-scanned on
         * every cold start.
         */
        allow();
        resolves();
        lenient().when(idResolver.curIdentifiers(eq(42L), any())).thenReturn(
                new ResourceIdResolver.CurIdentifiers(Map.of(), Map.of(), Map.of(), Set.of()));

        /*
         * MAX_LOOKUP_IDS ids, ARN-shaped but short, because full-length ARNs cannot reach this
         * count: 100 of them is ~5 KB and MAX_IDS_CHARS refuses the request at 3 KB first. That is
         * intended — for ARN-shaped ids the character budget binds before the count does, and the
         * client reports how many connections it was able to price. The key's length does not
         * depend on the ids either way, since they are hashed, which is the property being pinned.
         */
        List<String> arns = new java.util.ArrayList<>();
        for (int i = 0; i < CurAdvancedMetricsService.MAX_LOOKUP_IDS; i++) {
            arns.add(String.format("arn:aws:s3:::bkt-%04d", i));
        }
        String ids = String.join(",", arns);
        assertTrue(ids.length() <= CurAdvancedMetricsService.MAX_IDS_CHARS, ids.length() + "");
        controller.resourceCosts(ACCOUNT_ID, ids, "2026-08", null);
        List<String> reversed = new java.util.ArrayList<>(arns);
        java.util.Collections.reverse(reversed);
        controller.resourceCosts(ACCOUNT_ID, String.join(",", reversed), "2026-08", null);

        ArgumentCaptor<String> suffix = ArgumentCaptor.forClass(String.class);
        verify(insightCache, times(2)).getOrCompute(eq(ACCOUNT_ID), suffix.capture(), any());
        String first = suffix.getAllValues().get(0);
        // PREFIX(13) + account(12) + "::" + the suffix, so the whole key stays near 154.
        assertTrue(CurInsightCache.PREFIX.length() + ACCOUNT_ID.length() + 2 + first.length() <= 160,
                first);
        // Sorted before hashing, so two readers asking the same question hit the same entry.
        assertEquals(first, suffix.getAllValues().get(1));
    }

    @Test
    @DisplayName("Amortized on a trimmed report is a 422 naming the columns, through this endpoint too")
    void costsSurfaceTheAmortizedRefusal() {
        allow();
        resolves();
        when(idResolver.curIdentifiers(eq(42L), any())).thenReturn(
                new ResourceIdResolver.CurIdentifiers(
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of("i-0abc", List.of("i-0abc")),
                        Map.of(),
                        Set.of()));
        when(metricsService.getResourceCosts(any(), any(), any(), any(), any(),
                eq(CostBasis.AMORTIZED)))
                .thenThrow(new ReportCannotAnswerException("the amortized cost basis",
                        List.of("reservation_effective_cost")));

        ResponseEntity<?> response =
                controller.resourceCosts(ACCOUNT_ID, "i-0abc", "2026-08", "amortized");

        assertEquals(422, response.getStatusCode().value());
        assertEquals(List.of("reservation_effective_cost"), body(response).get("missingColumns"));
    }

    @Test
    @DisplayName("An unusable period on /resource-costs is a 400, and nothing is cached or queried")
    void costsRefuseAnUnusablePeriod() {
        allow();

        ResponseEntity<?> response = controller.resourceCosts(ACCOUNT_ID, "i-0abc", "2026-13", null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid period", body(response).get("error"));
        verifyNoInteractions(insightCache, idResolver, metricsService);
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
