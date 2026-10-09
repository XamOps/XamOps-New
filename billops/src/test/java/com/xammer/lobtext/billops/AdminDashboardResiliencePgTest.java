package com.xammer.lobtext.billops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AccountSummaryDto;
import com.xammer.billops.dto.AdminDashboardDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.UserDashboardDto;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.service.AccountAccessHelper;
import com.xammer.billops.service.AdminDashboardService;
import com.xammer.billops.service.BillingService;
import com.xammer.billops.service.CostService;
import com.xammer.billops.service.RedisCacheService;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin dashboard isolation over a throwaway local PostgreSQL (AC 21-23, AC 7 for billops; design D7,
 * D8, §7.2). A JDK proxy around {@link BillOpsCachedDataRepository} makes {@code findById} of chosen
 * keys raise a real PostgreSQL error ({@link Poisoner}), and each case checks that only that read
 * fails: every other figure, list and snapshot comes out right, and each failed read logs exactly one
 * WARN naming key, account id and account name.
 *
 * <p>Both public methods run in both caller shapes: the web path, with one {@code EntityManager}
 * bound for the request by {@link OpenEntityManagerInViewInterceptor} itself (as for
 * {@code DashboardController} under open-in-view), and the scheduler path with nothing bound (as for
 * {@code DashboardCacheScheduler} and {@code BillopsCacheRefreshService}).
 *
 * <p>Fixture: an AWS, a GCP and an Azure account, each with its own client; inline cache rows as main
 * writes them, plus one legacy large-object {@code GCP_MONTHLY_TOTAL} row holding {@code 1953.07};
 * two invoices; two tickets with {@code @Lob} descriptions. Redis, Cost Explorer ({@link CostService}),
 * {@link BillingService} and {@link AccountAccessHelper} (primary admin) are mocks.
 */
@SpringBootTest(classes = LobTextBillopsPgConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({AdminDashboardService.class, DashboardFaultInjectionConfig.class})
@ActiveProfiles("lobtext-pg")
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminDashboardResiliencePgTest {

    private static final String DATABASE = "lobtext_b_dashboard";

    private static final String ACCESS_WARN = "Admin dashboard cache access failed";
    private static final String SNAPSHOT_WARN = "Admin dashboard snapshot read failed";
    private static final String REDIS_KEY = "billops:admin:dashboard:system";
    private static final String SNAPSHOT_KEY = "BILLOPS_ADMIN_DASHBOARD::system";
    private static final String TOP_SPENDING_KEY = "BILLOPS_TOP_SPENDING::system";

    private static final String AWS_ID = "111122223333";
    private static final String GCP_PROJECT = "lobtext-gcp-project";
    private static final String AZURE_SUBSCRIPTION = "lobtext-azure-subscription";
    private static final String MTD_KEY = "AWS_MTD_COST::" + AWS_ID;
    private static final String TICKET_TEXT = "Why did the ₹ bill change? — café";

    /** Cost Explorer's live month-to-date total for the AWS account (the mocked {@link CostService}). */
    private static final double LIVE_AWS_MTD = 150.25;

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        // No write-mode setting: the base application.properties bridge supplies the legacy default.
    }

    @MockitoBean
    private RedisCacheService redisCache;

    @MockitoBean
    private CostService costService;

    @MockitoBean
    private BillingService billingService;

    @MockitoBean
    private AccountAccessHelper accountAccessHelper;

    @Autowired
    private AdminDashboardService service;

    @Autowired
    private PoisoningPostProcessor poisoning;

    @Autowired
    private BillOpsCachedDataRepository cachedData;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AdminDashboardService.class);
    private final Logger typeLogger = (Logger) LoggerFactory.getLogger(LegacyLobTextType.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private Level serviceLevel;

    private YearMonth current;
    private YearMonth last;
    private Fixture fixture;

    private enum Caller { WEB_REQUEST, SCHEDULER }

    private record Account(long id, String name, String provider, String identifier, long clientId, String clientName) {
    }

    private record Fixture(Account aws, Account gcp, Account azure, String gcpRef, long creatorId,
                           LocalDate awsInvoiceDate, LocalDate gcpInvoiceDate) {
    }

    @BeforeEach
    void seedFixtureAndMocks() {
        poisoning.reset();
        current = YearMonth.now();
        last = current.minusMonths(1);
        fixture = seed();

        when(accountAccessHelper.filterAccounts(anyList())).thenAnswer(call -> call.getArgument(0));
        when(accountAccessHelper.getAllowedDbIds()).thenReturn(null);      // primary admin: no restriction
        when(redisCache.get(anyString(), eq(AdminDashboardDto.class))).thenReturn(Optional.empty());
        when(costService.getCostHistory(any(CloudAccount.class), anyInt(), anyInt(), eq(true)))
                .thenReturn(List.of(Map.of("date", current.toString(), "cost", LIVE_AWS_MTD)));
        // billingService.getAccountBillingSummary returns null: the last fallback yields ZERO.

        serviceLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        log.start();
        serviceLogger.addAppender(log);
        typeLogger.addAppender(log);
    }

    @AfterEach
    void releaseLogsAndPoison() {
        poisoning.reset();
        serviceLogger.detachAppender(log);
        typeLogger.detachAppender(log);
        serviceLogger.setLevel(serviceLevel);
    }

    // ------------------------------------------------------------------ safety and wiring

    @Test
    void runsOnTheThrowawayDatabaseWithTheCacheRepositoryWrapped() throws SQLException {
        assertThat(environment.getActiveProfiles()).containsExactly("lobtext-pg");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_database(), host(inet_server_addr())")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(DATABASE);
            assertThat(rs.getString(2)).isIn("127.0.0.1", "::1");
        }
        assertThat(Proxy.isProxyClass(cachedData.getClass())).as("cache repository wrapped").isTrue();
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.LEGACY);
    }

    // ------------------------------------------------------------------ getAdminDashboardSummary (AC 21)

    @Test
    void summaryInAWebRequestSurvivesAFailedCacheRead() throws Exception {
        summarySurvivesAFailedCacheRead(Caller.WEB_REQUEST);
    }

    @Test
    void summaryFromTheSchedulerSurvivesAFailedCacheRead() throws Exception {
        summarySurvivesAFailedCacheRead(Caller.SCHEDULER);
    }

    private void summarySurvivesAFailedCacheRead(Caller caller) throws Exception {
        String poisoned = awsBreakdownKey(current);
        poisoning.poison(poisoned);

        AdminDashboardDto dashboard = call(caller, () -> service.getAdminDashboardSummary(true));
        poisoning.stop();

        // The poisoned exact-date breakdown falls through to AWS_MTD_COST (95.5), the next fallback.
        assertThat(dashboard.getTotalAccounts()).isEqualTo(3L);
        assertThat(dashboard.getAwsAccountsCount()).isEqualTo(1L);
        assertThat(dashboard.getGcpAccountsCount()).isEqualTo(1L);
        assertThat(dashboard.getAzureAccountsCount()).isEqualTo(1L);
        assertThat(dashboard.getAccountsByStatus()).isEqualTo(Map.of("ACTIVE", 3L));
        assertThat(dashboard.getTotalClients()).isEqualTo(3L);
        assertThat(dashboard.getPendingInvoices()).isEqualTo(1L);
        assertThat(dashboard.getFinalizedInvoices()).isEqualTo(1L);
        assertThat(dashboard.getOpenTickets()).isEqualTo(1L);

        List<AccountSummaryDto> accounts = dashboard.getTopSpendingAccounts();
        assertThat(accounts).extracting(AccountSummaryDto::getProvider).containsExactly("GCP", "Azure", "AWS");
        assertAccount(accounts, fixture.aws(), 95.5, 88.5, fixture.awsInvoiceDate());
        assertAccount(accounts, fixture.gcp(), 1953.07, 1500.25, fixture.gcpInvoiceDate());   // AC 7: the LO's value
        assertAccount(accounts, fixture.azure(), 312.5, 275.0, null);
        assertThat(dashboard.getTotalSpending()).isEqualByComparingTo("2361.07");
        assertThat(dashboard.getSpendingByProvider()).containsOnlyKeys("AWS", "GCP", "Azure");
        assertThat(dashboard.getSpendingByProvider().get("AWS")).isEqualByComparingTo("95.5");
        assertThat(dashboard.getSpendingByProvider().get("GCP")).isEqualByComparingTo("1953.07");
        assertThat(dashboard.getSpendingByProvider().get("Azure")).isEqualByComparingTo("312.5");

        assertRecentInvoicesAndTickets(dashboard);
        assertSpendingTrend(dashboard.getSpendingTrend(), 95.5 + 1953.07 + 312.5);

        // The full build is cached as today (D8): Redis key and TTL, and the DB snapshot.
        verify(redisCache).put(eq(REDIS_KEY), any(AdminDashboardDto.class), eq(120L));
        String snapshotRef = raw(SNAPSHOT_KEY);
        assertThat(LegacyLobTextType.isCandidate(snapshotRef)).as("legacy-mode snapshot write: %s", snapshotRef).isTrue();
        AdminDashboardDto snapshot = snapshot();
        assertThat(snapshot.getTotalAccounts()).isEqualTo(3L);
        assertThat(snapshot.getTotalSpending()).isEqualByComparingTo("2361.07");
        assertThat(snapshot.getTopSpendingAccounts()).extracting(AccountSummaryDto::getClientName)
                .containsExactly(fixture.gcp().clientName(), fixture.azure().clientName(), fixture.aws().clientName());
        assertThat(snapshot.getRecentInvoices()).hasSize(2);
        assertThat(snapshot.getRecentTickets()).hasSize(2);
        assertThat(snapshot.getSpendingTrend()).hasSize(6);

        assertThat(raw(gcpKey(current))).as("reads leave the legacy row as it was").isEqualTo(fixture.gcpRef());
        assertOneWarnPerFailedRead(fixture.aws(), current);
        assertThat(poisoning.poisonedCalls()).as("current month and its trend point").containsExactly(poisoned, poisoned);
    }

    // ------------------------------------------------------------------ refreshTopSpendingAccountsLive (AC 22)

    @Test
    void liveTopSpendingInAWebRequestSurvivesAFailedMtdRead() throws Exception {
        liveTopSpendingSurvivesAFailedMtdRead(Caller.WEB_REQUEST);
    }

    @Test
    void liveTopSpendingFromTheSchedulerSurvivesAFailedMtdRead() throws Exception {
        liveTopSpendingSurvivesAFailedMtdRead(Caller.SCHEDULER);
    }

    private void liveTopSpendingSurvivesAFailedMtdRead(Caller caller) throws Exception {
        service.getAdminDashboardSummary(true);     // the last full build, whose snapshot the live refresh patches
        log.list.clear();
        poisoning.poison(MTD_KEY);

        List<AccountSummaryDto> top = call(caller, service::refreshTopSpendingAccountsLive);
        poisoning.stop();

        // The failed AWS_MTD_COST read only skips its write-back; the live Cost Explorer figure stands.
        assertThat(top).extracting(AccountSummaryDto::getProvider).containsExactly("GCP", "Azure", "AWS");
        assertAccount(top, fixture.aws(), LIVE_AWS_MTD, 88.5, fixture.awsInvoiceDate());
        assertAccount(top, fixture.gcp(), 1953.07, 1500.25, fixture.gcpInvoiceDate());
        assertAccount(top, fixture.azure(), 312.5, 275.0, null);
        assertThat(raw(MTD_KEY)).as("no write-back after the failed read").isEqualTo("95.5");

        List<AccountSummaryDto> saved = objectMapper.readValue(payload(TOP_SPENDING_KEY), new TypeReference<>() {});
        assertThat(saved).extracting(AccountSummaryDto::getAccountName)
                .containsExactly(fixture.gcp().name(), fixture.azure().name(), fixture.aws().name());

        // The snapshot is patched: fresh top 10 and total, everything else from the full build.
        AdminDashboardDto snapshot = snapshot();
        assertThat(snapshot.getTotalAccounts()).isEqualTo(3L);
        assertThat(snapshot.getTotalSpending()).isEqualByComparingTo("2415.82");
        assertAccount(snapshot.getTopSpendingAccounts(), fixture.aws(), LIVE_AWS_MTD, 88.5, fixture.awsInvoiceDate());
        assertAccount(snapshot.getTopSpendingAccounts(), fixture.gcp(), 1953.07, 1500.25, fixture.gcpInvoiceDate());
        assertAccount(snapshot.getTopSpendingAccounts(), fixture.azure(), 312.5, 275.0, null);
        assertRecentInvoicesAndTickets(snapshot);
        assertSpendingTrend(snapshot.getSpendingTrend(), 120.75 + 1953.07 + 312.5);
        verify(redisCache, times(2)).put(eq(REDIS_KEY), any(AdminDashboardDto.class), eq(120L));   // build + patch
        verify(costService).getCostHistory(any(CloudAccount.class), eq(current.getYear()), eq(current.getMonthValue()), eq(true));

        assertOneWarnPerFailedRead(fixture.aws(), current);
        assertThat(poisoning.poisonedCalls()).containsExactly(MTD_KEY);
    }

    @Test
    void liveTopSpendingInAWebRequestSurvivesAFailedSnapshotRead() throws Exception {
        liveTopSpendingSurvivesAFailedSnapshotRead(Caller.WEB_REQUEST);
    }

    @Test
    void liveTopSpendingFromTheSchedulerSurvivesAFailedSnapshotRead() throws Exception {
        liveTopSpendingSurvivesAFailedSnapshotRead(Caller.SCHEDULER);
    }

    private void liveTopSpendingSurvivesAFailedSnapshotRead(Caller caller) throws Exception {
        poisoning.poison(SNAPSHOT_KEY);

        List<AccountSummaryDto> top = call(caller, service::refreshTopSpendingAccountsLive);
        poisoning.stop();

        assertThat(top).extracting(AccountSummaryDto::getProvider).containsExactly("GCP", "Azure", "AWS");
        assertAccount(top, fixture.aws(), LIVE_AWS_MTD, 88.5, fixture.awsInvoiceDate());
        assertThat(payload(MTD_KEY)).as("live write-back").isEqualTo(Double.toString(LIVE_AWS_MTD));

        // An unreadable snapshot is patched from an empty dashboard, as before the change.
        AdminDashboardDto snapshot = snapshot();
        assertThat(snapshot.getTopSpendingAccounts()).extracting(AccountSummaryDto::getProvider)
                .containsExactly("GCP", "Azure", "AWS");
        assertThat(snapshot.getTotalSpending()).isEqualByComparingTo("2415.82");

        assertThat(poisoning.poisonedCalls()).containsExactly(SNAPSHOT_KEY);
        List<ILoggingEvent> warnings = events(Level.WARN, AdminDashboardService.class);
        assertThat(warnings).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).startsWith(SNAPSHOT_WARN + " key=" + SNAPSHOT_KEY + ": ")
                    .contains("large object 0 does not exist");
            assertThat(e.getThrowableProxy()).as("stack traces only at DEBUG").isNull();
        });
        assertNoLaterFailures();
    }

    // ------------------------------------------------------------------ assertions

    private void assertAccount(List<AccountSummaryDto> accounts, Account expected, double currentMonth,
                               double lastMonth, LocalDate lastInvoice) {
        assertThat(accounts).filteredOn(a -> a.getId() == expected.id()).singleElement().satisfies(a -> {
            assertThat(a.getAccountName()).isEqualTo(expected.name());
            assertThat(a.getProvider()).isEqualTo(expected.provider());
            assertThat(a.getAccountIdentifier()).isEqualTo(expected.identifier());
            assertThat(a.getStatus()).isEqualTo("ACTIVE");
            assertThat(a.getClientName()).as("client of %s", expected.name()).isEqualTo(expected.clientName());
            assertThat(a.getClientId()).isEqualTo(expected.clientId());
            assertThat(a.getCurrentMonthSpending()).as("current month of %s", expected.name())
                    .isEqualByComparingTo(BigDecimal.valueOf(currentMonth));
            assertThat(a.getLastMonthSpending()).as("last month of %s", expected.name())
                    .isEqualByComparingTo(BigDecimal.valueOf(lastMonth));
            assertThat(a.getLastInvoiceDate()).isEqualTo(lastInvoice);
        });
    }

    private void assertRecentInvoicesAndTickets(AdminDashboardDto dashboard) {
        assertThat(dashboard.getRecentInvoices())
                .extracting(InvoiceDto::getInvoiceNumber, InvoiceDto::getAccountName, InvoiceDto::getProvider,
                        InvoiceDto::getClientName, InvoiceDto::getStatus)
                .containsExactly(
                        tuple("INV-GCP-1", fixture.gcp().name(), "GCP", fixture.gcp().clientName(), "DRAFT"),
                        tuple("INV-AWS-1", fixture.aws().name(), "AWS", fixture.aws().clientName(), "FINALIZED"));
        assertThat(dashboard.getRecentTickets())
                .extracting(TicketDto::getSubject, TicketDto::getDescription, TicketDto::getStatus, TicketDto::getCreatorId)
                .containsExactly(
                        tuple("Billing question", TICKET_TEXT, "OPEN", fixture.creatorId()),
                        tuple("Old question", "Resolved already", "CLOSED", null));
    }

    private void assertSpendingTrend(List<UserDashboardDto.CostHistory> trend, double currentMonthTotal) {
        assertThat(trend).extracting(UserDashboardDto.CostHistory::getDate).containsExactly(
                current.minusMonths(5).toString(), current.minusMonths(4).toString(), current.minusMonths(3).toString(),
                current.minusMonths(2).toString(), last.toString(), current.toString());
        assertThat(trend.get(5).getCost()).isCloseTo(currentMonthTotal, within(1e-6));
        assertThat(trend.get(4).getCost()).isCloseTo(88.5 + 1500.25 + 275.0, within(1e-6));
        assertThat(trend.subList(0, 4)).allSatisfy(point -> assertThat(point.getCost()).isZero());
    }

    /** AC 23: exactly one WARN per poisoned read, naming key, account id and name; stacks only at DEBUG. */
    private void assertOneWarnPerFailedRead(Account account, YearMonth month) {
        List<String> calls = poisoning.poisonedCalls();
        List<ILoggingEvent> warnings = events(Level.WARN, AdminDashboardService.class);
        assertThat(calls).as("poisoned calls").isNotEmpty();
        assertThat(warnings).as("every dashboard WARN is a cache-access WARN")
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).startsWith(ACCESS_WARN + " "));
        assertThat(warnings).as("one WARN per poisoned call").hasSameSizeAs(calls);
        for (int i = 0; i < calls.size(); i++) {
            ILoggingEvent e = warnings.get(i);
            assertThat(e.getFormattedMessage())
                    .contains("key=" + calls.get(i) + " ")
                    .contains("accountId=" + account.id() + " ")
                    .contains("account=" + account.name() + " ")
                    .contains("provider=" + account.provider() + " ")
                    .contains("month=" + month + ": ")
                    .contains("large object 0 does not exist");
            assertThat(e.getThrowableProxy()).as("stack traces only at DEBUG").isNull();
        }
        assertThat(events(Level.DEBUG, AdminDashboardService.class))
                .filteredOn(e -> e.getThrowableProxy() != null)
                .as("one DEBUG with the stack per WARN").hasSameSizeAs(calls);
        assertNoLaterFailures();
    }

    /** FR-4.2: nothing else failed after the poisoned read, and the type found nothing unreadable. */
    private void assertNoLaterFailures() {
        assertThat(log.list).noneSatisfy(e -> assertThat(e.getFormattedMessage())
                .containsAnyOf("current transaction is aborted", "LazyInitializationException", "could not initialize proxy"));
        assertThat(events(Level.WARN, LegacyLobTextType.class)).isEmpty();
    }

    private List<ILoggingEvent> events(Level level, Class<?> source) {
        return log.list.stream()
                .filter(e -> e.getLevel() == level && e.getLoggerName().equals(source.getName()))
                .toList();
    }

    // ------------------------------------------------------------------ callers

    /**
     * Runs {@code action} as the web path (one EntityManager bound for the request by the real
     * open-in-view interceptor, then closed) or the scheduler path (nothing bound).
     */
    private <T> T call(Caller caller, Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.hasResource(emf)).as("nothing bound before the call").isFalse();
        if (caller == Caller.SCHEDULER) {
            return action.get();
        }
        OpenEntityManagerInViewInterceptor openInView = new OpenEntityManagerInViewInterceptor();
        openInView.setEntityManagerFactory(emf);
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        openInView.preHandle(request);
        try {
            Object holder = TransactionSynchronizationManager.getResource(emf);
            assertThat(holder).isInstanceOf(EntityManagerHolder.class);
            T result = action.get();
            assertThat(TransactionSynchronizationManager.getResource(emf)).as("the request's EntityManager").isSameAs(holder);
            return result;
        } finally {
            openInView.afterCompletion(request, null);
        }
    }

    // ------------------------------------------------------------------ fixture

    private Fixture seed() {
        PgTestSupport.execute(url,
                "TRUNCATE cached_data, invoice, ticket, cloud_account, app_user, client RESTART IDENTITY CASCADE");
        LocalDate awsInvoiceDate = last.atEndOfMonth();
        LocalDate gcpInvoiceDate = current.atDay(1);
        LocalDateTime now = LocalDateTime.now();

        Fixture seeded = new TransactionTemplate(transactionManager).execute(status -> {
            Client awsClient = persist(new Client("Lobtext AWS Client"));
            Client gcpClient = persist(new Client("Lobtext GCP Client"));
            Client azureClient = persist(new Client("Lobtext Azure Client"));

            CloudAccount aws = account("Lobtext AWS", "AWS", awsClient);
            aws.setAwsAccountId(AWS_ID);
            CloudAccount gcp = account("Lobtext GCP", "GCP", gcpClient);
            gcp.setGcpProjectId(GCP_PROJECT);
            CloudAccount azure = account("Lobtext Azure", "Azure", azureClient);
            azure.setAzureSubscriptionId(AZURE_SUBSCRIPTION);
            persist(aws);
            persist(gcp);
            persist(azure);

            User creator = new User("lobtext-dashboard-user", "unused", awsClient);
            creator.setEmail("lobtext-dashboard-user@example.invalid");
            persist(creator);

            persist(invoice("INV-AWS-1", aws, awsClient, awsInvoiceDate, Invoice.InvoiceStatus.FINALIZED, "1000.00"));
            persist(invoice("INV-GCP-1", gcp, gcpClient, gcpInvoiceDate, Invoice.InvoiceStatus.DRAFT, "2000.00"));
            persist(ticket(awsClient, creator, "Billing question", TICKET_TEXT, "OPEN", now.minusHours(1)));
            persist(ticket(gcpClient, null, "Old question", "Resolved already", "CLOSED", now.minusHours(2)));

            return new Fixture(ref(aws, AWS_ID, awsClient), ref(gcp, GCP_PROJECT, gcpClient),
                    ref(azure, AZURE_SUBSCRIPTION, azureClient), null, creator.getId(), awsInvoiceDate, gcpInvoiceDate);
        });

        // Cache rows: inline values as main writes them, plus one legacy large-object reference.
        insertRaw(awsBreakdownKey(current),
                "[{\"service\":\"AmazonEC2\",\"amount\":100.5},{\"service\":\"AmazonS3\",\"amount\":20.25}]");
        insertRaw(MTD_KEY, "95.5");
        insertRaw("AWS_MONTHLY_TOTAL::" + AWS_ID + "::" + last, "88.5");
        String gcpRef = Long.toString(PgTestSupport.createLoText(url, "1953.07"));
        insertRaw(gcpKey(current), gcpRef);
        insertRaw(gcpKey(last), "1500.25");
        insertRaw("AZURE_COST_BILLING_SUMMARY::" + AZURE_SUBSCRIPTION,
                "[{\"monthToDateCost\":300.5},{\"monthToDateCost\":12.0}]");
        insertRaw("AZURE_COST_HISTORY::" + AZURE_SUBSCRIPTION,
                "{\"labels\":[\"" + last.format(DateTimeFormatter.ofPattern("MMM yyyy")) + "\"],\"costs\":[275.0]}");

        return new Fixture(seeded.aws(), seeded.gcp(), seeded.azure(), gcpRef, seeded.creatorId(),
                seeded.awsInvoiceDate(), seeded.gcpInvoiceDate());
    }

    private <E> E persist(E entity) {
        entityManager.persist(entity);
        return entity;
    }

    private static CloudAccount account(String name, String provider, Client client) {
        CloudAccount account = new CloudAccount();
        account.setAccountName(name);
        account.setProvider(provider);
        account.setStatus("ACTIVE");
        account.setClient(client);
        return account;
    }

    private static Account ref(CloudAccount account, String identifier, Client client) {
        return new Account(account.getId(), account.getAccountName(), account.getProvider(), identifier,
                client.getId(), client.getName());
    }

    private static Invoice invoice(String number, CloudAccount account, Client client, LocalDate date,
                                   Invoice.InvoiceStatus status, String amount) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setInvoiceDate(date);
        invoice.setBillingPeriod(YearMonth.from(date).toString());
        invoice.setStatus(status);
        invoice.setAmount(new BigDecimal(amount));
        invoice.setCloudAccount(account);
        invoice.setClient(client);
        return invoice;
    }

    private static Ticket ticket(Client client, User creator, String subject, String description, String status,
                                 LocalDateTime createdAt) {
        Ticket ticket = new Ticket();
        ticket.setClient(client);
        ticket.setCreator(creator);
        ticket.setSubject(subject);
        ticket.setDescription(description);
        ticket.setStatus(status);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return ticket;
    }

    private static String awsBreakdownKey(YearMonth month) {
        return "AWS_COST_BREAKDOWN::" + AWS_ID + "::SERVICE::::" + month.atDay(1) + "::" + month.atEndOfMonth();
    }

    private static String gcpKey(YearMonth month) {
        return "GCP_MONTHLY_TOTAL::" + GCP_PROJECT + "::" + month;
    }

    private AdminDashboardDto snapshot() throws Exception {
        return objectMapper.readValue(payload(SNAPSHOT_KEY), AdminDashboardDto.class);
    }

    /** The decoded payload, read through the mapped type. */
    private String payload(String key) {
        return cachedData.findById(key).map(CachedData::getJsonData)
                .orElseThrow(() -> new AssertionError("no cached_data row " + key));
    }

    private static void insertRaw(String key, String raw) {
        PgTestSupport.execute(url, "INSERT INTO cached_data (cache_key, json_data, last_updated) VALUES (?, ?, ?)",
                key, raw, LocalDateTime.now());
    }

    private static String raw(String key) {
        return PgTestSupport.rawText(url, "cached_data", "cache_key", key, "json_data");
    }
}
