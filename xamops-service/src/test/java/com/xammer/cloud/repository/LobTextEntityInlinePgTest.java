package com.xammer.cloud.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.OidShapeGuard;
import com.xammer.cloud.domain.type.PgTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.CustomType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xammer.cloud.domain.type.PgTestSupport.NON_ASCII_CONTENT;
import static com.xammer.cloud.repository.LegacyLobTextTypeInlinePgTest.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * xamops-service, inline write mode, for {@code cloudsitter_policies.schedule_json},
 * {@code github_integration_config.encrypted_pat} and {@code sonarqube_project.encrypted_token}:
 * OID-shaped values are refused with no row and no new large object (AC 19), and other values are
 * stored as plain text (the AC 16 inline bullet, for AC 17).
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LobTextEntityInlinePgTest {

    private static final String DATABASE = "lobtext_x_entity_inline";
    private static final String SONAR_URL = "https://sonar.example.invalid";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        registry.add("spring.jpa.properties." + LobTextWriteMode.PROPERTY, () -> "inline");
    }

    @Autowired
    private CloudsitterPolicyRepository policies;

    @Autowired
    private GitHubIntegrationConfigRepository gitHubConfigs;

    @Autowired
    private SonarQubeProjectRepository sonarProjects;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;
    private final List<Logger> loggers = new ArrayList<>();
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void setUp() {
        owner = persistOwner("lobtext-inline-owner-" + SEQUENCE.incrementAndGet());
        log = new ListAppender<>();
        log.start();
        for (Class<?> source : new Class<?>[] {LegacyLobTextType.class, OidShapeGuard.class}) {
            Logger logger = (Logger) LoggerFactory.getLogger(source);
            logger.addAppender(log);
            loggers.add(logger);
        }
    }

    @AfterEach
    void releaseLogs() {
        loggers.forEach(l -> l.detachAppender(log));
        loggers.clear();
    }

    @Test
    void inlineModeReachesEveryMappedType() {
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        Map.of(CloudsitterPolicy.class, "scheduleJson",
                GitHubIntegrationConfig.class, "encryptedPat",
                SonarQubeProject.class, "encryptedToken").forEach((entity, attribute) -> {
            CustomType<?> type = (CustomType<?>) sf.getMappingMetamodel().getEntityDescriptor(entity).getPropertyType(attribute);
            assertThat(((LegacyLobTextType) type.getUserType()).writeMode()).as(attribute).isEqualTo(LobTextWriteMode.INLINE);
        });
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.INLINE);
    }

    @Test
    void ac19PersistingAnOidShapedValueIsRefusedForEachColumnAndWritesNothing() {
        long objects = PgTestSupport.loCount(url);
        long policyRows = count("cloudsitter_policies");
        long configRows = count("github_integration_config");
        long projectRows = count("sonarqube_project");

        assertRefused(() -> policies.save(newPolicy("ac19", "16842")));
        assertRefused(() -> gitHubConfigs.save(new GitHubIntegrationConfig(owner, "acme", "ac19", "16842")));
        assertRefused(() -> sonarProjects.save(new SonarQubeProject(owner, "ac19", SONAR_URL, "acme_ac19", "16842")));

        assertThat(count("cloudsitter_policies")).isEqualTo(policyRows);
        assertThat(count("github_integration_config")).isEqualTo(configRows);
        assertThat(count("sonarqube_project")).isEqualTo(projectRows);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Refused OID-shaped value table=cloudsitter_policies key=new",
                "Refused OID-shaped value table=github_integration_config key=new",
                "Refused OID-shaped value table=sonarqube_project key=new");
    }

    @Test
    void ac19UpdatingAScheduleToAnOidShapedValueIsRefusedAndKeepsTheStoredText() {
        CloudsitterPolicy policy = policies.save(newPolicy("ac19-update", "{\"MONDAY\":[9]}"));
        long objects = PgTestSupport.loCount(url);

        policy.setScheduleJson("16842");
        assertRefused(() -> policies.save(policy));

        assertThat(raw("cloudsitter_policies", policy.getId(), "schedule_json")).isEqualTo("{\"MONDAY\":[9]}");
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Refused OID-shaped value table=cloudsitter_policies key=" + policy.getId());
    }

    @Test
    void inlineWritesStorePlainTextInEachColumnAndCreateNoObject() {
        String schedule = "{\"note\":\"" + NON_ASCII_CONTENT + "\"}";
        long objects = PgTestSupport.loCount(url);

        CloudsitterPolicy policy = policies.save(newPolicy("inline", schedule));
        GitHubIntegrationConfig config = gitHubConfigs.save(
                new GitHubIntegrationConfig(owner, "acme", "inline-" + SEQUENCE.incrementAndGet(), NON_ASCII_CONTENT));
        SonarQubeProject project = sonarProjects.save(
                new SonarQubeProject(owner, "inline", SONAR_URL, "acme_inline", NON_ASCII_CONTENT));

        assertThat(raw("cloudsitter_policies", policy.getId(), "schedule_json")).isEqualTo(schedule);
        assertThat(raw("github_integration_config", config.getId(), "encrypted_pat")).isEqualTo(NON_ASCII_CONTENT);
        assertThat(raw("sonarqube_project", project.getId(), "encrypted_token")).isEqualTo(NON_ASCII_CONTENT);
        assertThat(policies.findById(policy.getId()).orElseThrow().getScheduleJson()).isEqualTo(schedule);
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static CloudsitterPolicy newPolicy(String name, String scheduleJson) {
        CloudsitterPolicy policy = new CloudsitterPolicy();
        policy.setName(name);
        policy.setType("SCHEDULE");
        policy.setTimeZone("Asia/Kolkata");
        policy.setScheduleJson(scheduleJson);
        return policy;
    }

    private User persistOwner(String username) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Client client = new Client();
            client.setName(username + "-client");
            entityManager.persist(client);
            User user = new User();
            user.setUsername(username);
            user.setPassword("x");
            user.setRole("XAMOPS");
            user.setClient(client);
            entityManager.persist(user);
            return user;
        });
    }

    private List<ILoggingEvent> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static long count(String table) {
        return PgTestSupport.queryLong(url, "SELECT count(*) FROM " + table);
    }

    private static String raw(String table, Object id, String column) {
        return PgTestSupport.rawText(url, table, "id", id, column);
    }
}
