package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.ImpersonationFilter;
import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.controller.LoginOtpController;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.exception.NoUserPermissionDetail;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may read and change CloudK8s automation (design §6.2, §6.6, D2, §17).
 *
 * Entitlement itself is CloudAccountAccessService's (tested there); here it is mocked so each
 * case controls exactly which rows the caller holds.
 */
class K8sAutomationAccessServiceTest {

    private static final long USER_ID = 10L;
    private static final String CLUSTER = "prod-eks";
    /** Base64 of a 48-byte test-only key (HS256 needs 32+), as in ImpersonationJwtAsyncDispatchTest. */
    private static final String JWT_SECRET = "dGVzdC1vbmx5LWltcGVyc29uYXRpb24tc2lnbmluZy1rZXktbm90LWEtc2VjcmV0";

    private CloudAccountAccessService cloudAccountAccessService;
    private CloudAccountRepository cloudAccountRepository;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private UserRepository userRepository;
    private UserTabAccessRepository userTabAccessRepository;
    private DemoClientMembership demoClientMembership;
    private K8sAutomationAccessService service;

    @BeforeEach
    void setUp() {
        cloudAccountAccessService = mock(CloudAccountAccessService.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        userRepository = mock(UserRepository.class);
        userTabAccessRepository = mock(UserTabAccessRepository.class);
        demoClientMembership = new DemoClientMembership(userRepository);
        service = new K8sAutomationAccessService(cloudAccountAccessService, cloudAccountRepository,
                clusterConfigRepository, userRepository, userTabAccessRepository, demoClientMembership);
        when(cloudAccountRepository.findByAwsAccountId(anyString())).thenReturn(List.of());
        when(cloudAccountRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(userTabAccessRepository.findTabNamesByUserId(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        ImpersonationContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static CloudAccount account(long id, String awsId, String accessType, String status) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setAwsAccountId(awsId);
        a.setProvider("AWS");
        a.setAccessType(accessType);
        a.setStatus(status);
        a.setAccountName("acct-" + id);
        return a;
    }

    private static ClientUserDetails principal(long id, String username) {
        return new ClientUserDetails(username, "pw", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")),
                1L, id, "default");
    }

    private void userRow(long id, String email, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("user-" + id);
        u.setEmail(email);
        u.setRole(role);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        // DemoClientMembership loads via findByIdWithClient (JOIN FETCH client) to avoid lazy init
        // in the pre-transaction guard; stub it the same way.
        when(userRepository.findByIdWithClient(id)).thenReturn(Optional.of(u));
    }

    private void demoClientUserRow(long id, String email, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("user-" + id);
        u.setEmail(email);
        u.setRole(role);
        com.xammer.cloud.domain.Client client = new com.xammer.cloud.domain.Client("Demo Workspace");
        client.setId(900L);
        client.setDemo(true);
        u.setClient(client);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(userRepository.findByIdWithClient(id)).thenReturn(Optional.of(u));
    }

    private static KubernetesClusterConfig config(long id, long cloudAccountId, String name) {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(id);
        c.setCloudAccountId(cloudAccountId);
        c.setClusterName(name);
        c.setRegion("us-east-1");
        return c;
    }

    private void entitled(CloudAccount... accounts) {
        for (CloudAccount a : accounts) {
            when(cloudAccountRepository.findByAwsAccountId(a.getAwsAccountId())).thenReturn(List.of(a));
            when(cloudAccountAccessService.canAccess(any(), eq(a))).thenReturn(true);
        }
    }

    // ── Entitlement ───────────────────────────────────────────────────────

    @Test
    void resolveAccounts_padsAShortAccountNumber() {
        CloudAccount acct = account(7, "080310056620", "read-write", "CONNECTED");
        entitled(acct);
        assertThat(service.resolveAccounts(principal(USER_ID, "jane"), "80310056620")).containsExactly(acct);
    }

    @Test
    void resolveAccounts_alsoMatchesTheRawForm() {
        CloudAccount legacyUnpadded = account(8, "12345", "read-write", "CONNECTED");
        entitled(legacyUnpadded);
        assertThat(service.resolveAccounts(principal(USER_ID, "jane"), "12345")).containsExactly(legacyUnpadded);
    }

    @Test
    void resolveAccounts_returnsBothRowsOfTheLeadingZeroQuirk_paddedFirst() {
        CloudAccount padded = account(7, "080310056620", "read-write", "CONNECTED");
        CloudAccount raw = account(9, "80310056620", "read-only", "CONNECTED");
        entitled(padded, raw);
        assertThat(service.resolveAccounts(principal(USER_ID, "jane"), "80310056620")).containsExactly(padded, raw);
    }

    @Test
    void resolveAccounts_unknownAndNotYoursGetTheSameRefusal() {
        CloudAccount foreign = account(7, "999999999999", "read-write", "CONNECTED");
        when(cloudAccountRepository.findByAwsAccountId("999999999999")).thenReturn(List.of(foreign));
        when(cloudAccountAccessService.canAccess(any(), eq(foreign))).thenReturn(false);

        K8sAutomationRefusedException notYours = catchRefusal(() ->
                service.resolveAccounts(principal(USER_ID, "jane"), "999999999999"));
        K8sAutomationRefusedException unknown = catchRefusal(() ->
                service.resolveAccounts(principal(USER_ID, "jane"), "111111111111"));

        assertThat(notYours.getReason()).isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS);
        assertThat(unknown.getReason()).isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS);
        assertThat(notYours.getMessage()).isEqualTo(unknown.getMessage())
                .isEqualTo("You don't have access to this account.");
        assertThat(notYours.getStatus().value()).isEqualTo(403);
    }

    @Test
    void resolveAccounts_ignoresNonAwsRowsWithTheSameIdentifier() {
        CloudAccount gcp = account(7, "123456789012", "read-write", "CONNECTED");
        gcp.setProvider("GCP");
        when(cloudAccountRepository.findByAwsAccountId("123456789012")).thenReturn(List.of(gcp));
        when(cloudAccountAccessService.canAccess(any(), any())).thenReturn(true);
        assertThat(catchRefusal(() -> service.resolveAccounts(principal(USER_ID, "jane"), "123456789012"))
                .getReason()).isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1234567890123", "12 34", "-1"})
    void resolveAccounts_invalidNumbersAreRefusedLikeUnknownOnes(String bad) {
        assertThat(catchRefusal(() -> service.resolveAccounts(principal(USER_ID, "jane"), bad)).getReason())
                .isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS);
    }

    @Test
    void anotherTenantsCloudAccountIdIsNeverUsedAsAConfigKey() {
        // Tenant B's CloudAccount.id is 42 and it owns a modern config for the same cluster name.
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(42L, CLUSTER))
                .thenReturn(Optional.of(config(500, 42, CLUSTER)));

        assertThat(catchRefusal(() -> service.resolve(principal(USER_ID, "attacker"), "42", CLUSTER)).getReason())
                .isEqualTo(K8sAutomationReason.NO_ACCOUNT_ACCESS);
        verify(clusterConfigRepository, never()).findByCloudAccountIdAndClusterName(eq(42L), anyString());
    }

    // ── Config lookup ─────────────────────────────────────────────────────

    @Test
    void resolve_findsTheModernConfigByDbId_owningRowFirst() {
        CloudAccount padded = account(7, "080310056620", "read-only", "CONNECTED");
        CloudAccount raw = account(9, "80310056620", "read-write", "CONNECTED");
        entitled(padded, raw);
        KubernetesClusterConfig cfg = config(100, 9, CLUSTER);
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(9L, CLUSTER)).thenReturn(Optional.of(cfg));

        K8sAutomationRequestContext ctx = service.resolve(principal(USER_ID, "jane"), "80310056620", CLUSTER);

        assertThat(ctx.config()).isSameAs(cfg);
        assertThat(ctx.accounts()).containsExactly(raw, padded);
        assertThat(ctx.owningAccount()).contains(raw);
        assertThat(ctx.gateAccounts()).containsExactly(raw);
    }

    @Test
    void resolve_fallsBackToTheLegacyConfigKeyedByTheAwsNumber() {
        CloudAccount acct = account(7, "080310056620", "read-write", "CONNECTED");
        entitled(acct);
        KubernetesClusterConfig legacy = config(101, 80310056620L, CLUSTER);
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(80310056620L, CLUSTER))
                .thenReturn(Optional.of(legacy));

        K8sAutomationRequestContext ctx = service.resolve(principal(USER_ID, "jane"), "080310056620", CLUSTER);

        assertThat(ctx.config()).isSameAs(legacy);
        assertThat(ctx.owningAccount()).isEmpty();
        assertThat(ctx.primaryAccount()).isSameAs(acct);
        assertThat(ctx.gateAccounts()).containsExactly(acct);
    }

    @Test
    void resolve_neverReadsAnotherTenantsModernConfigAsALegacyOne() {
        CloudAccount acct = account(7, "000000000042", "read-write", "CONNECTED");
        entitled(acct);
        CloudAccount otherTenant = account(42, "555555555555", "read-write", "CONNECTED");
        when(cloudAccountRepository.findById(42L)).thenReturn(Optional.of(otherTenant));
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(42L, CLUSTER))
                .thenReturn(Optional.of(config(500, 42, CLUSTER)));

        K8sAutomationRequestContext ctx = service.resolve(principal(USER_ID, "jane"), "000000000042", CLUSTER);

        assertThat(ctx.config()).isNull();
        verify(clusterConfigRepository, never()).findByCloudAccountIdAndClusterName(42L, CLUSTER);
    }

    @Test
    void resolve_unregisteredClusterHasANullConfig() {
        entitled(account(7, "080310056620", "read-write", "CONNECTED"));
        assertThat(service.resolve(principal(USER_ID, "jane"), "080310056620", CLUSTER).config()).isNull();
    }

    @Test
    void configsFor_listsModernThenLegacy_deduplicatedByName() {
        CloudAccount acct = account(7, "080310056620", "read-write", "CONNECTED");
        when(clusterConfigRepository.findByCloudAccountId(7L))
                .thenReturn(List.of(config(1, 7, "a"), config(2, 7, "b")));
        when(clusterConfigRepository.findByCloudAccountId(80310056620L))
                .thenReturn(List.of(config(3, 80310056620L, "b"), config(4, 80310056620L, "c")));

        assertThat(service.configsFor(List.of(acct))).extracting(KubernetesClusterConfig::getId)
                .containsExactly(1L, 2L, 4L);
    }

    @Test
    void findOwningAccount_byDbIdElseByPaddedAwsNumber() {
        CloudAccount modern = account(7, "080310056620", "read-write", "CONNECTED");
        when(cloudAccountRepository.findById(7L)).thenReturn(Optional.of(modern));
        assertThat(service.findOwningAccount(config(1, 7, CLUSTER))).contains(modern);

        CloudAccount legacyOwner = account(8, "080310056620", "read-only", "CONNECTED");
        when(cloudAccountRepository.findByAwsAccountId("080310056620")).thenReturn(List.of(legacyOwner));
        assertThat(service.findOwningAccount(config(2, 80310056620L, CLUSTER))).contains(legacyOwner);

        assertThat(service.findOwningAccount(config(3, 123L, CLUSTER))).isEmpty();
    }

    // ── Account gates ─────────────────────────────────────────────────────

    @Test
    void theOwningRowDecidesTheAccountGates() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        CloudAccount owner = account(9, "80310056620", "read-write", "CONNECTED");
        CloudAccount other = account(7, "080310056620", "read-only", "PENDING");
        K8sAutomationRequestContext ctx = new K8sAutomationRequestContext(
                principal(USER_ID, "jane"), List.of(owner, other), config(100, 9, CLUSTER));

        assertThat(service.writeDecision(ctx.user(), ctx.gateAccounts()).allowed()).isTrue();
    }

    @Test
    void withoutAnOwnerTheStricterRowWins() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        CloudAccount rw = account(7, "080310056620", "read-write", "CONNECTED");
        CloudAccount ro = account(9, "80310056620", "read-only", "PENDING");
        K8sAutomationRequestContext ctx = new K8sAutomationRequestContext(
                principal(USER_ID, "jane"), List.of(rw, ro), config(101, 80310056620L, CLUSTER));

        assertThat(service.writeDecision(ctx.user(), ctx.gateAccounts()).reasons())
                .containsExactly(K8sAutomationReason.READ_ONLY_CONNECTION, K8sAutomationReason.CONNECTION_NOT_VERIFIED);
    }

    @Test
    void readOnlyNullAndUnverifiedConnections() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        ClientUserDetails jane = principal(USER_ID, "jane");

        assertThat(service.writeDecision(jane, List.of(account(1, "1", "read-only", "CONNECTED"))).reasons())
                .containsExactly(K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(service.writeDecision(jane, List.of(account(1, "1", null, "CONNECTED"))).reasons())
                .containsExactly(K8sAutomationReason.READ_ONLY_CONNECTION);
        assertThat(service.writeDecision(jane, List.of(account(1, "1", "READ-WRITE", "CONNECTED"))).allowed())
                .isTrue();
        assertThat(service.writeDecision(jane, List.of(account(1, "1", "read-write", "PENDING"))).reasons())
                .containsExactly(K8sAutomationReason.CONNECTION_NOT_VERIFIED);
    }

    @Test
    void notVerifiedDependsOnStatusOnly_blankRoleArnStillPasses() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        CloudAccount acct = account(1, "1", "read-write", "CONNECTED");
        acct.setRoleArn("");
        acct.setExternalId(null);
        assertThat(service.writeDecision(principal(USER_ID, "jane"), List.of(acct)).allowed()).isTrue();
    }

    // ── Demo ──────────────────────────────────────────────────────────────

    @Test
    void demoIsDecidedByTheUserRowsEmail_notTheUsername() {
        userRow(1L, "demo.xamops@xammer.in", "XAMOPS");
        userRow(2L, "  Demo.BillOps@Xammer.IN ", "BILLOPS");
        userRow(3L, "jane@corp.example", "XAMOPS");

        assertThat(service.userDecision(principal(1L, "Xammer Admin")).reasons())
                .containsExactly(K8sAutomationReason.DEMO);
        assertThat(service.userDecision(principal(2L, "Xammer BillOps")).reasons())
                .containsExactly(K8sAutomationReason.DEMO);
        // A non-demo user whose username happens to be the demo address is not demo.
        assertThat(service.userDecision(principal(3L, "demo.xamops@xammer.in")).canChangeAutomation()).isTrue();
    }

    @Test
    void demoRefusesFirstEvenOnAReadOnlyUnverifiedConnection() {
        userRow(1L, "demo.xamops@xammer.in", "XAMOPS");
        K8sAutomationAccessService.WriteDecision d =
                service.writeDecision(principal(1L, "Xammer Admin"), List.of(account(1, "1", "read-only", "PENDING")));

        assertThat(d.reasons()).containsExactly(K8sAutomationReason.DEMO,
                K8sAutomationReason.READ_ONLY_CONNECTION, K8sAutomationReason.CONNECTION_NOT_VERIFIED);
        assertThat(d.detail()).isNull();
        assertThat(d.firstRefusal()).get().extracting(K8sAutomationRefusedException::getReason)
                .isEqualTo(K8sAutomationReason.DEMO);
    }

    @Test
    void aDemoClientUserIsRefusedAsDemo_evenWithANonDemoEmail() {
        // Host-provisioned demo user: ordinary email, but attached to the is_demo client.
        demoClientUserRow(4L, "someone@gmail.com", "XAMOPS");
        assertThat(service.userDecision(principal(4L, "someone@gmail.com")).reasons())
                .containsExactly(K8sAutomationReason.DEMO);
    }

    @Test
    void aNonDemoClientUserWithANonDemoEmailIsStillAllowed() {
        // The user's client defaults to is_demo=false (no client set), so neither demo gate fires.
        userRow(5L, "real.user@corp.example", "XAMOPS");
        assertThat(service.userDecision(principal(5L, "real.user@corp.example")).canChangeAutomation()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoEmailsMirrorLoginOtpController() throws Exception {
        Field field;
        try {
            field = LoginOtpController.class.getDeclaredField("DEMO_EMAILS");
        } catch (NoSuchFieldException e) {
            fail("DEMO_EMAILS moved; update K8sAutomationAccessService");
            return;
        }
        field.setAccessible(true);
        Set<String> loginDemoEmails = (Set<String>) field.get(null);
        assertThat(K8sAutomationAccessService.DEMO_EMAILS)
                .as("DEMO_EMAILS moved; update K8sAutomationAccessService")
                .containsExactlyInAnyOrderElementsOf(loginDemoEmails);
    }

    // ── Impersonation ─────────────────────────────────────────────────────

    @Test
    void impersonatedSessionsCantChangeAutomation_evenAsAnUnrestrictedXamopsUser() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        ImpersonationContext.setImpersonatedUserId(USER_ID);

        K8sAutomationAccessService.UserDecision d = service.userDecision(principal(USER_ID, "jane"));

        assertThat(d.reasons()).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
        assertThat(d.detail()).isEqualTo(NoUserPermissionDetail.IMPERSONATION);
    }

    @Test
    void impersonationThroughTheHeaderPathIsRefused() throws Exception {
        userRow(77L, "customer@corp.example", "XAMOPS");
        ImpersonationFilter filter = new ImpersonationFilter();
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/eks/autoscaling/prod-eks/enabled");
        request.addHeader(ImpersonationFilter.IMPERSONATION_HEADER, "77");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("X-ORIGINAL-ADMIN-ID", 1L); // an admin already verified in this session
        request.setSession(session);

        AtomicReference<K8sAutomationAccessService.UserDecision> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(service.userDecision(
                (ClientUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal())));

        assertThat(seen.get().reasons()).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
        assertThat(seen.get().detail()).isEqualTo(NoUserPermissionDetail.IMPERSONATION);
    }

    @Test
    void impersonationThroughTheBillopsJwtPathIsRefused() throws Exception {
        userRow(77L, "customer@corp.example", "XAMOPS");
        ImpersonationJwtFilter filter = new ImpersonationJwtFilter();
        ReflectionTestUtils.setField(filter, "secretKey", JWT_SECRET);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.invokeMethod(filter, "initSigningKey");

        String token = Jwts.builder()
                .setSubject("77")
                .setIssuer("billops")
                .claim("role", "XAMOPS")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET)), SignatureAlgorithm.HS256)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/eks/autoscaling/prod-eks/enabled");
        request.setRequestURI("/api/eks/autoscaling/prod-eks/enabled");
        request.addHeader("Authorization", "Bearer " + token);

        AtomicReference<K8sAutomationAccessService.UserDecision> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(service.userDecision(
                (ClientUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal())));

        assertThat(seen.get()).as("the filter reached the chain").isNotNull();
        assertThat(seen.get().reasons()).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
        assertThat(seen.get().detail()).isEqualTo(NoUserPermissionDetail.IMPERSONATION);
    }

    // ── Roles ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"XAMOPS", "USER", "ROLE_XAMOPS", "XAMOPS_ADMIN", "SUPER_ADMIN", "ADMIN", " xamops "})
    void xamopsProductRolesMayChangeAutomation(String role) {
        userRow(USER_ID, "jane@corp.example", role);
        assertThat(service.userDecision(principal(USER_ID, "jane")).canChangeAutomation()).as(role).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BILLOPS", "ROLE_BILLOPS_ADMIN", "BILLOPS_ADMIN_SUB", "AUDITOR", ""})
    void billopsOnlyAndUnknownRolesAreRefusedWithRole(String role) {
        userRow(USER_ID, "jane@corp.example", role);
        K8sAutomationAccessService.UserDecision d = service.userDecision(principal(USER_ID, "jane"));
        assertThat(d.reasons()).as(role).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
        assertThat(d.detail()).isEqualTo(NoUserPermissionDetail.ROLE);
    }

    @Test
    void roleNormalisationAddsThePrefix() {
        assertThat(K8sAutomationAccessService.normalizeRole("XAMOPS")).isEqualTo("ROLE_XAMOPS");
        assertThat(K8sAutomationAccessService.normalizeRole("USER")).isEqualTo("ROLE_USER");
        assertThat(K8sAutomationAccessService.normalizeRole("ROLE_ADMIN")).isEqualTo("ROLE_ADMIN");
        assertThat(K8sAutomationAccessService.normalizeRole(null)).isEmpty();
    }

    // ── Tab grant ─────────────────────────────────────────────────────────

    @Test
    void unrestrictedUsersMayChangeAutomation() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        assertThat(service.userDecision(principal(USER_ID, "jane")).canChangeAutomation()).isTrue();
    }

    @Test
    void restrictedUsersNeedBothKeys() {
        userRow(USER_ID, "jane@corp.example", "XAMOPS");
        ClientUserDetails jane = principal(USER_ID, "jane");

        when(userTabAccessRepository.findTabNamesByUserId(USER_ID))
                .thenReturn(List.of("cloudops-cloudk8s", "cloudops-cloudk8s-automation", "finops-dashboard"));
        assertThat(service.userDecision(jane).canChangeAutomation()).isTrue();

        for (List<String> tabs : List.of(
                List.of("cloudops-cloudk8s"),
                List.of("cloudops-cloudk8s-automation"),
                List.of("finops-dashboard"))) {
            when(userTabAccessRepository.findTabNamesByUserId(USER_ID)).thenReturn(tabs);
            K8sAutomationAccessService.UserDecision d = service.userDecision(jane);
            assertThat(d.reasons()).as(tabs.toString()).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
            assertThat(d.detail()).isEqualTo(NoUserPermissionDetail.TAB_GRANT);
            assertThat(service.writeDecision(jane, List.of(account(1, "1", "read-write", "CONNECTED")))
                    .firstRefusal()).get().satisfies(e -> {
                        assertThat(e.getReason()).isEqualTo(K8sAutomationReason.NO_USER_PERMISSION);
                        assertThat(e.getDetail()).isEqualTo(NoUserPermissionDetail.TAB_GRANT);
                    });
        }
    }

    // ── Fail closed ───────────────────────────────────────────────────────

    @Test
    void aPrincipalWithoutAnIdOrUserRowFailsClosed() {
        ClientUserDetails noId = new ClientUserDetails("ghost", "pw", List.of(), 1L, null, "default");
        assertThat(service.userDecision(noId).detail()).isEqualTo(NoUserPermissionDetail.TAB_GRANT);
        assertThat(service.userDecision(noId).reasons()).containsExactly(K8sAutomationReason.NO_USER_PERMISSION);

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        when(userRepository.findByIdWithClient(99L)).thenReturn(Optional.empty());
        assertThat(service.userDecision(principal(99L, "ghost")).reasons())
                .containsExactly(K8sAutomationReason.NO_USER_PERMISSION);
        assertThat(service.writeDecision(null, List.of(account(1, "1", "read-write", "CONNECTED"))).allowed())
                .isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static K8sAutomationRefusedException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (K8sAutomationRefusedException e) {
            return e;
        }
        throw new AssertionError("expected a K8sAutomationRefusedException");
    }

    @Test
    void refusalHelperRejectsCallsThatDontRefuse() {
        assertThatThrownBy(() -> catchRefusal(() -> { })).isInstanceOf(AssertionError.class);
    }
}
