package com.xammer.cloud.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationWebConfig;
import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.xammer.cloud.security.K8sAutomationTestWorld.CLUSTER;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_A_AWS;
import static com.xammer.cloud.security.K8sAutomationTestWorld.TENANT_B_DB_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code preHandle} and {@code afterCompletion} of the guard, driven directly with the URI
 * variables Spring would have set (design §6.6, §17).
 */
class K8sAutomationGuardInterceptorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private K8sAutomationTestWorld world;
    private K8sAutomationGuardInterceptor interceptor;
    private ClientUserDetails owner;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        world = new K8sAutomationTestWorld();
        interceptor = new K8sAutomationGuardInterceptor(world.accessService);
        owner = world.addUser(10L, 1L, "owner@tenant-a.example", "XAMOPS", "ROLE_XAMOPS", List.of());
        K8sAutomationTestWorld.signIn(owner);
        logger = (Logger) LoggerFactory.getLogger(K8sAutomationGuardInterceptor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static MockHttpServletRequest request(String method, String clusterVariable, String cluster,
                                                  String accountId, String suffix) {
        String path = "/api/eks/autoscaling/" + cluster + suffix;
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        Map<String, String> vars = new HashMap<>();
        if (cluster != null) {
            vars.put(clusterVariable, cluster);
        }
        r.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        if (accountId != null) {
            r.setParameter("accountId", accountId);
        }
        return r;
    }

    private static MockHttpServletRequest request(String method, String accountId, String suffix) {
        return request(method, "clusterName", CLUSTER, accountId, suffix);
    }

    private record Outcome(boolean proceed, MockHttpServletResponse response, MockHttpServletRequest request) {
        JsonNode body() throws Exception {
            return JSON.readTree(response.getContentAsString());
        }
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceed = interceptor.preHandle(request, response, new Object());
        return new Outcome(proceed, response, request);
    }

    private static void assertRefused(Outcome o, int status, String code) throws Exception {
        assertThat(o.proceed()).isFalse();
        assertThat(o.response().getStatus()).isEqualTo(status);
        assertThat(o.response().getContentType()).startsWith("application/json");
        JsonNode body = o.body();
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.has("message")).isTrue();
        assertThat(body.has("field")).isTrue();
        assertThat(body.has("detail")).isTrue();
        assertThat(o.request().getAttribute(K8sAutomationRequestContext.ATTRIBUTE)).isNull();
    }

    // ── Registration ──────────────────────────────────────────────────────

    /** A request with its path parsed, as the DispatcherServlet does before matching interceptors. */
    private static MockHttpServletRequest parsed(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(r);
        return r;
    }

    /** Exposes the registered interceptors (the getter is protected). */
    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    @Test
    void theWebConfigRegistersTheGuardOnEveryAutomationPath() {
        assertThat(K8sAutomationWebConfig.GUARDED_PATHS).containsExactly("/api/eks/autoscaling/**");
        ExposedRegistry registry = new ExposedRegistry();
        new K8sAutomationWebConfig(interceptor).addInterceptors(registry);

        assertThat(registry.registered()).singleElement().isInstanceOfSatisfying(MappedInterceptor.class, mapped -> {
            assertThat(mapped.getInterceptor()).isSameAs(interceptor);
            assertThat(mapped.getIncludePathPatterns()).containsExactly("/api/eks/autoscaling/**");
            for (String method : List.of("GET", "PUT", "POST", "DELETE", "PATCH")) {
                assertThat(mapped.matches(parsed(method, "/api/eks/autoscaling/prod-eks/node-templates/t1")))
                        .as(method).isTrue();
            }
            assertThat(mapped.matches(parsed("GET", "/api/xamops/k8s/clusters"))).isFalse();
        });
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void readsPassAfterResolution_andStoreTheContext(String method) throws Exception {
        Outcome o = run(request(method, TENANT_A_AWS, ""));

        assertThat(o.proceed()).isTrue();
        K8sAutomationRequestContext ctx =
                (K8sAutomationRequestContext) o.request().getAttribute(K8sAutomationRequestContext.ATTRIBUTE);
        assertThat(ctx.config()).isSameAs(world.tenantAConfig);
        assertThat(ctx.primaryAccount()).isSameAs(world.tenantA);
        assertThat(ctx.user()).isSameAs(owner);
    }

    @Test
    void readsNeedOnlyEntitlement_evenOnAReadOnlyConnection() throws Exception {
        world.tenantA.setAccessType("read-only");
        world.tenantA.setStatus("PENDING");
        assertThat(run(request("GET", TENANT_A_AWS, "/node-fleet")).proceed()).isTrue();
    }

    @Test
    void theEngineClusterVariableResolvesLikeClusterName() throws Exception {
        Outcome o = run(request("GET", "cluster", CLUSTER, TENANT_A_AWS, ""));
        assertThat(o.proceed()).isTrue();
        assertThat(((K8sAutomationRequestContext) o.request().getAttribute(K8sAutomationRequestContext.ATTRIBUTE))
                .config()).isSameAs(world.tenantAConfig);

        Outcome bad = run(request("PUT", "cluster", "-bad", TENANT_A_AWS, ""));
        assertRefused(bad, 400, "K8S_INVALID_REQUEST");
        assertThat(bad.body().get("field").asText()).isEqualTo("cluster");
    }

    @Test
    void aCorsPreflightIsLeftToSpring() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest preflight = request("OPTIONS", TENANT_A_AWS, "/enabled");
        preflight.addHeader("Origin", "https://app.example");
        preflight.addHeader("Access-Control-Request-Method", "PUT");
        assertThat(run(preflight).proceed()).isTrue();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST", "DELETE", "PATCH"})
    void aReadWriteUnrestrictedUserPassesAndIsMarkedForCompletionLogging(String method) throws Exception {
        Outcome o = run(request(method, TENANT_A_AWS, "/enabled"));
        assertThat(o.proceed()).isTrue();
        assertThat(o.request().getAttribute(K8sAutomationGuardInterceptor.MUTATION_ATTRIBUTE)).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST", "DELETE"})
    void everyWriteGateReturnsItsCode(String method) throws Exception {
        // Demo, by the user row's email; the username is "Xammer Admin" in production.
        K8sAutomationTestWorld.signIn(world.addUser(20L, 1L, "demo.xamops@xammer.in", "XAMOPS", "ROLE_XAMOPS", List.of()));
        assertRefused(run(request(method, TENANT_A_AWS, "/rebalance")), 403, "K8S_DEMO");

        // Impersonated session.
        K8sAutomationTestWorld.signIn(owner);
        ImpersonationContext.setImpersonatedUserId(owner.getId());
        Outcome impersonated = run(request(method, TENANT_A_AWS, "/rebalance"));
        assertRefused(impersonated, 403, "K8S_NO_USER_PERMISSION");
        assertThat(impersonated.body().get("detail").asText()).isEqualTo("IMPERSONATION");
        ImpersonationContext.clear();

        // Role that can't change automation.
        K8sAutomationTestWorld.signIn(world.addUser(21L, 1L, "billing@tenant-a.example", "BILLOPS", "ROLE_BILLOPS", List.of()));
        Outcome role = run(request(method, TENANT_A_AWS, "/rebalance"));
        assertRefused(role, 403, "K8S_NO_USER_PERMISSION");
        assertThat(role.body().get("detail").asText()).isEqualTo("ROLE");

        // Restricted user without the permission key.
        K8sAutomationTestWorld.signIn(world.addUser(22L, 1L, "member@tenant-a.example", "XAMOPS", "ROLE_XAMOPS",
                List.of("cloudops-cloudk8s")));
        Outcome grant = run(request(method, TENANT_A_AWS, "/rebalance"));
        assertRefused(grant, 403, "K8S_NO_USER_PERMISSION");
        assertThat(grant.body().get("detail").asText()).isEqualTo("TAB_GRANT");
        assertThat(grant.body().get("message").asText()).isEqualTo("You don't have permission to change automation.");

        // Read-only, then unverified, connection.
        K8sAutomationTestWorld.signIn(owner);
        world.tenantA.setAccessType("read-only");
        Outcome readOnly = run(request(method, TENANT_A_AWS, "/rebalance"));
        assertRefused(readOnly, 403, "K8S_READ_ONLY_CONNECTION");
        assertThat(readOnly.body().get("detail").isNull()).isTrue();
        world.tenantA.setAccessType("read-write");
        world.tenantA.setStatus("FAILED");
        assertRefused(run(request(method, TENANT_A_AWS, "/rebalance")), 403, "K8S_CONNECTION_NOT_VERIFIED");
    }

    @Test
    void aRestrictedUserWithBothKeysPasses() throws Exception {
        K8sAutomationTestWorld.signIn(world.addUser(23L, 1L, "lead@tenant-a.example", "XAMOPS", "ROLE_XAMOPS",
                List.of("cloudops-cloudk8s", "cloudops-cloudk8s-automation")));
        assertThat(run(request("PUT", TENANT_A_AWS, "/mode")).proceed()).isTrue();
    }

    // ── Input format ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"-starts-with-dash", "has space", "dots.not.allowed", "_x", "semi;colon"})
    void badClusterNamesAre400(String cluster) throws Exception {
        Outcome o = run(request("GET", "clusterName", cluster, TENANT_A_AWS, ""));
        assertRefused(o, 400, "K8S_INVALID_REQUEST");
        assertThat(o.body().get("field").asText()).isEqualTo("clusterName");
        assertThat(o.body().get("message").asText()).doesNotContain(cluster);
    }

    @Test
    void aClusterNameOver100CharactersIs400() throws Exception {
        assertRefused(run(request("GET", "clusterName", "a".repeat(101), TENANT_A_AWS, "")), 400, "K8S_INVALID_REQUEST");
        assertThat(run(request("GET", "clusterName", "a".repeat(100), TENANT_A_AWS, "")).response().getStatus())
                .isEqualTo(404); // valid name, just not registered
    }

    @Test
    void aMissingClusterVariableIs400() throws Exception {
        assertRefused(run(request("GET", "clusterName", null, TENANT_A_AWS, "")), 400, "K8S_INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1234567890123", "12 3", "-1"})
    void badAccountIdsAre400(String accountId) throws Exception {
        Outcome o = run(request("PUT", accountId, "/enabled"));
        assertRefused(o, 400, "K8S_INVALID_REQUEST");
        assertThat(o.body().get("field").asText()).isEqualTo("accountId");
    }

    @Test
    void aMissingAccountIdIs400() throws Exception {
        Outcome o = run(request("GET", null, ""));
        assertRefused(o, 400, "K8S_INVALID_REQUEST");
        assertThat(o.body().get("field").asText()).isEqualTo("accountId");
    }

    // ── Entitlement ───────────────────────────────────────────────────────

    @Test
    void anUnknownAccountGetsTheSameMessageAsNotYours() throws Exception {
        Outcome unknown = run(request("GET", "333333333333", ""));
        Outcome notYours = run(request("GET", K8sAutomationTestWorld.TENANT_B_AWS, ""));

        assertRefused(unknown, 403, "K8S_NO_ACCOUNT_ACCESS");
        assertRefused(notYours, 403, "K8S_NO_ACCOUNT_ACCESS");
        assertThat(unknown.body().get("message").asText()).isEqualTo(notYours.body().get("message").asText())
                .isEqualTo("You don't have access to this account.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "POST", "DELETE"})
    void anotherTenantsCloudAccountIdIsRefused_andItsConfigIsNeverLookedUp(String method) throws Exception {
        assertRefused(run(request(method, String.valueOf(TENANT_B_DB_ID), "/enabled")), 403, "K8S_NO_ACCOUNT_ACCESS");
        verify(world.clusterConfigRepository, never()).findByCloudAccountIdAndClusterName(eq(TENANT_B_DB_ID), anyString());
    }

    @Test
    void anotherTenantsClusterNameResolvesOnlyToTheCallersOwnConfig() throws Exception {
        // Both tenants named their cluster prod-eks; tenant A's request must never see tenant B's row.
        Outcome o = run(request("PUT", TENANT_A_AWS, "/enabled"));
        assertThat(((K8sAutomationRequestContext) o.request().getAttribute(K8sAutomationRequestContext.ATTRIBUTE))
                .config().getId()).isEqualTo(K8sAutomationTestWorld.TENANT_A_CONFIG_ID);
        verify(world.clusterConfigRepository, never()).findByCloudAccountIdAndClusterName(eq(TENANT_B_DB_ID), anyString());
    }

    // ── Registration and principal ────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "POST", "DELETE"})
    void aClusterWithoutAConfigIs404ForEveryMethod(String method) throws Exception {
        assertRefused(run(request(method, "clusterName", "unregistered", TENANT_A_AWS, "")), 404,
                "K8S_CLUSTER_NOT_REGISTERED");
    }

    @Test
    void aPrincipalThatIsntClientUserDetailsIs401() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("agent", null, List.of()));
        assertRefused(run(request("GET", TENANT_A_AWS, "")), 401, "K8S_UNAUTHENTICATED");

        SecurityContextHolder.clearContext();
        assertRefused(run(request("PUT", TENANT_A_AWS, "/enabled")), 401, "K8S_UNAUTHENTICATED");
    }

    @Test
    void aRepositoryFailureIsAFixed500WithoutExceptionText() throws Exception {
        when(world.cloudAccountRepository.findByAwsAccountId(anyString()))
                .thenThrow(new IllegalStateException("connection refused: db-host-17:5432"));
        Outcome o = run(request("PUT", TENANT_A_AWS, "/enabled"));
        assertRefused(o, 500, "K8S_INTERNAL");
        assertThat(o.response().getContentAsString()).doesNotContain("db-host-17").doesNotContain("refused");
        assertThat(o.body().get("message").asText()).isEqualTo("Couldn't save the change. Try again.");
    }

    // ── Audit logging ─────────────────────────────────────────────────────

    @Test
    void refusalsAreLoggedInPreHandle_warnForNoAccountAccess() throws Exception {
        world.tenantA.setAccessType("read-only");
        run(request("PUT", TENANT_A_AWS, "/evictor-policy"));
        run(request("GET", K8sAutomationTestWorld.TENANT_B_AWS, ""));

        assertThat(logs.list).hasSize(2);
        ILoggingEvent readOnly = logs.list.get(0);
        assertThat(readOnly.getLevel()).isEqualTo(Level.INFO);
        assertThat(readOnly.getFormattedMessage())
                .contains("user=10", "account=7", "cluster=prod-eks", "method=PUT",
                        "path=/api/eks/autoscaling/prod-eks/evictor-policy", "status=403",
                        "code=K8S_READ_ONLY_CONNECTION");
        ILoggingEvent foreign = logs.list.get(1);
        assertThat(foreign.getLevel()).isEqualTo(Level.WARN);
        assertThat(foreign.getFormattedMessage()).contains("code=K8S_NO_ACCOUNT_ACCESS", "status=403", "user=10");
    }

    @Test
    void impersonatedRefusalsLogTheImpersonatedUser() throws Exception {
        ImpersonationContext.setImpersonatedUserId(10L);
        run(request("PUT", TENANT_A_AWS, "/mode"));
        assertThat(logs.list).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("impersonatedUser=10", "code=K8S_NO_USER_PERMISSION"));
    }

    @Test
    void completedMutationsAreLoggedInAfterCompletion_readsAreNot() throws Exception {
        Outcome mutation = run(request("PUT", TENANT_A_AWS, "/safety-guards"));
        assertThat(logs.list).isEmpty();
        mutation.response().setStatus(200);
        interceptor.afterCompletion(mutation.request(), mutation.response(), new Object(), null);

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("K8s automation mutation", "user=10", "account=7",
                    "cluster=prod-eks", "method=PUT", "path=/api/eks/autoscaling/prod-eks/safety-guards", "status=200");
        });

        Outcome read = run(request("GET", TENANT_A_AWS, ""));
        interceptor.afterCompletion(read.request(), read.response(), new Object(), null);
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void invalidInputIsNeverLogged() throws Exception {
        // The decoded path variable can hold a newline; the request URI a container reports is encoded.
        MockHttpServletRequest r = request("GET", "clusterName", "evil\nFORGED", TENANT_A_AWS, "");
        r.setRequestURI("/api/eks/autoscaling/evil%0AFORGED");
        run(r);
        assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("\n").contains("cluster=null", "field=clusterName"));
    }

    @Test
    void theAccessServiceIsNeverAskedForAWriteDecisionOnReads() throws Exception {
        world.tenantA.setAccessType("read-only");
        run(request("GET", TENANT_A_AWS, ""));
        verify(world.userTabAccessRepository, never()).findTabNamesByUserId(anyLong());
    }
}
