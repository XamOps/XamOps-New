package com.xammer.cloud.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.domain.type.PgTestSupport;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.CloudsitterEmailBuilder;
import com.xammer.cloud.service.CloudsitterService;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.EncryptionService;
import com.xammer.cloud.service.TenantService;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.KeyGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xammer.cloud.domain.type.PgTestSupport.NON_ASCII_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * xamops-service, legacy write mode, for the three other guarded columns:
 * {@code cloudsitter_policies.schedule_json}, {@code github_integration_config.encrypted_pat} and
 * {@code sonarqube_project.encrypted_token}. Covers AC 8 (decoded reads, credentials decrypt),
 * AC 14 (changing another field keeps the stored reference byte-identical) and AC 17 (legacy writes).
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LobTextEntityPgTest {

    private static final String DATABASE = "lobtext_x_entity";
    private static final String SCHEDULE_JSON = "{\"MONDAY\":[9,10,11,12,13,14,15,16,17],\"FRIDAY\":[9,10]}";
    private static final String SONAR_URL = "https://sonar.example.invalid";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        url = PgTestSupport.recreateDatabase(DATABASE);
        PgTestSupport.springProperties(url, "create-drop").forEach((k, v) -> registry.add(k, () -> v));
        registry.add("spring.jpa.properties." + LobTextWriteMode.PROPERTY, () -> "legacy");
    }

    @Autowired
    private CloudsitterPolicyRepository policies;

    @Autowired
    private GitHubIntegrationConfigRepository gitHubConfigs;

    @Autowired
    private SonarQubeProjectRepository sonarProjects;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private EncryptionService encryption;
    private User owner;
    private Logger typeLogger;
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        encryption = new EncryptionService();
        ReflectionTestUtils.setField(encryption, "secretKeyString",
                Base64.getEncoder().encodeToString(generator.generateKey().getEncoded()));
        ReflectionTestUtils.invokeMethod(encryption, "init");

        owner = persistOwner("lobtext-owner-" + SEQUENCE.incrementAndGet());

        typeLogger = (Logger) LoggerFactory.getLogger(LegacyLobTextType.class);
        log = new ListAppender<>();
        log.start();
        typeLogger.addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        typeLogger.detachAppender(log);
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    void ac8GetAllPoliciesReturnsTheDecodedScheduleOfALegacyReference() {
        CloudsitterPolicy policy = policies.save(newPolicy("ac8", "{}"));
        String ref = Long.toString(PgTestSupport.createLoText(url, SCHEDULE_JSON));
        PgTestSupport.setRaw(url, "cloudsitter_policies", "id", policy.getId(), "schedule_json", ref);

        CloudsitterService service = new CloudsitterService(policies, mock(CloudsitterAssignmentRepository.class),
                mock(AwsClientProvider.class), mock(CloudAccountRepository.class), new ObjectMapper(),
                mock(TenantService.class), mock(AwsPricingService.class), mock(EmailService.class),
                mock(CloudsitterEmailBuilder.class));

        assertThat(service.getAllPolicies()).filteredOn(p -> policy.getId().equals(p.getId()))
                .singleElement().extracting(CloudsitterPolicy::getScheduleJson).isEqualTo(SCHEDULE_JSON);
        assertThat(raw("cloudsitter_policies", policy.getId(), "schedule_json")).isEqualTo(ref);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void ac8LegacyReferenceCredentialsDecryptWithTheTestKey() {
        String pat = "fake-pat-for-lobtext-tests";
        String token = "fake-sonar-token-for-lobtext-tests";

        GitHubIntegrationConfig config = gitHubConfigs.save(
                new GitHubIntegrationConfig(owner, "acme", "ac8-" + SEQUENCE.incrementAndGet(), encryption.encrypt("placeholder")));
        PgTestSupport.setRaw(url, "github_integration_config", "id", config.getId(), "encrypted_pat",
                Long.toString(PgTestSupport.createLoText(url, encryption.encrypt(pat))));

        SonarQubeProject project = sonarProjects.save(
                new SonarQubeProject(owner, "ac8", SONAR_URL, "acme_ac8", encryption.encrypt("placeholder")));
        PgTestSupport.setRaw(url, "sonarqube_project", "id", project.getId(), "encrypted_token",
                Long.toString(PgTestSupport.createLoText(url, encryption.encrypt(token))));

        assertThat(encryption.decrypt(gitHubConfigs.findById(config.getId()).orElseThrow().getEncryptedPat()))
                .isEqualTo(pat);
        assertThat(encryption.decrypt(sonarProjects.findById(project.getId()).orElseThrow().getEncryptedToken()))
                .isEqualTo(token);
        assertThat(warnings()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 14

    @Test
    void ac14ChangingAnotherFieldLeavesEachLegacyReferenceByteIdentical() {
        CloudsitterPolicy policy = policies.save(newPolicy("before-rename", "{}"));
        String policyRef = Long.toString(PgTestSupport.createLoText(url, SCHEDULE_JSON));
        PgTestSupport.setRaw(url, "cloudsitter_policies", "id", policy.getId(), "schedule_json", policyRef);

        GitHubIntegrationConfig config = gitHubConfigs.save(
                new GitHubIntegrationConfig(owner, "acme", "ac14-" + SEQUENCE.incrementAndGet(), "placeholder"));
        String patRef = Long.toString(PgTestSupport.createLoText(url, encryption.encrypt("fake-pat")));
        PgTestSupport.setRaw(url, "github_integration_config", "id", config.getId(), "encrypted_pat", patRef);

        SonarQubeProject project = sonarProjects.save(
                new SonarQubeProject(owner, "before-rename", SONAR_URL, "acme_ac14", "placeholder"));
        String tokenRef = Long.toString(PgTestSupport.createLoText(url, encryption.encrypt("fake-token")));
        PgTestSupport.setRaw(url, "sonarqube_project", "id", project.getId(), "encrypted_token", tokenRef);

        long objects = PgTestSupport.loCount(url);

        CloudsitterPolicy loadedPolicy = policies.findById(policy.getId()).orElseThrow();
        loadedPolicy.setName("after-rename");
        policies.save(loadedPolicy);

        GitHubIntegrationConfig loadedConfig = gitHubConfigs.findById(config.getId()).orElseThrow();
        loadedConfig.setLastSyncedAt(Instant.parse("2026-10-02T09:30:00Z"));
        gitHubConfigs.save(loadedConfig);

        SonarQubeProject loadedProject = sonarProjects.findById(project.getId()).orElseThrow();
        loadedProject.setProjectName("after-rename");
        sonarProjects.save(loadedProject);

        assertThat(raw("cloudsitter_policies", policy.getId(), "schedule_json")).isEqualTo(policyRef);
        assertThat(raw("cloudsitter_policies", policy.getId(), "name")).isEqualTo("after-rename");
        assertThat(raw("github_integration_config", config.getId(), "encrypted_pat")).isEqualTo(patRef);
        assertThat(lastSyncedAt(config.getId())).isEqualTo(Instant.parse("2026-10-02T09:30:00Z"));
        assertThat(raw("sonarqube_project", project.getId(), "encrypted_token")).isEqualTo(tokenRef);
        assertThat(raw("sonarqube_project", project.getId(), "project_name")).isEqualTo("after-rename");
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
    }

    @Test
    void ac14AMissingPatReadsAsNullAndAnotherFieldCanStillBeUpdated() {
        GitHubIntegrationConfig config = gitHubConfigs.save(
                new GitHubIntegrationConfig(owner, "acme", "missing-" + SEQUENCE.incrementAndGet(), "placeholder"));
        long dangling = PgTestSupport.danglingOid(url);
        PgTestSupport.setRaw(url, "github_integration_config", "id", config.getId(), "encrypted_pat",
                Long.toString(dangling));
        long objects = PgTestSupport.loCount(url);

        GitHubIntegrationConfig loaded = gitHubConfigs.findById(config.getId()).orElseThrow();
        assertThat(loaded.getEncryptedPat()).isNull();
        assertThat(encryption.decrypt(loaded.getEncryptedPat())).isNull();   // today's undecryptable path
        loaded.setLastSyncedAt(Instant.parse("2026-10-03T10:00:00Z"));
        gitHubConfigs.save(loaded);

        assertThat(raw("github_integration_config", config.getId(), "encrypted_pat")).isEqualTo(Long.toString(dangling));
        assertThat(lastSyncedAt(config.getId())).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects);
        assertThat(warnings()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Unreadable legacy large-object reference table=github_integration_config"
                        + " column=encrypted_pat key=" + config.getId() + " oid=" + dangling + " reason=missing");
    }

    // ------------------------------------------------------------------ AC 17

    @Test
    void ac17LegacyWritesStoreAnOidForEachColumnAndKeepThePreviousObject() {
        String schedule = "{\"note\":\"" + NON_ASCII_CONTENT + "\"}";
        long objects = PgTestSupport.loCount(url);

        CloudsitterPolicy policy = policies.save(newPolicy("ac17", schedule));
        GitHubIntegrationConfig config = gitHubConfigs.save(
                new GitHubIntegrationConfig(owner, "acme", "ac17-" + SEQUENCE.incrementAndGet(), NON_ASCII_CONTENT));
        SonarQubeProject project = sonarProjects.save(
                new SonarQubeProject(owner, "ac17", SONAR_URL, "acme_ac17", NON_ASCII_CONTENT));
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects + 3);

        String policyRef = assertLegacyStored("cloudsitter_policies", policy.getId(), "schedule_json", schedule);
        String patRef = assertLegacyStored("github_integration_config", config.getId(), "encrypted_pat", NON_ASCII_CONTENT);
        String tokenRef = assertLegacyStored("sonarqube_project", project.getId(), "encrypted_token", NON_ASCII_CONTENT);
        assertThat(policies.findById(policy.getId()).orElseThrow().getScheduleJson()).isEqualTo(schedule);
        assertThat(gitHubConfigs.findById(config.getId()).orElseThrow().getEncryptedPat()).isEqualTo(NON_ASCII_CONTENT);
        assertThat(sonarProjects.findById(project.getId()).orElseThrow().getEncryptedToken()).isEqualTo(NON_ASCII_CONTENT);

        policy.setScheduleJson("{\"v\":2}");
        policies.save(policy);
        config.setEncryptedPat("second-pat");
        gitHubConfigs.save(config);
        project.setEncryptedToken("second-token");
        sonarProjects.save(project);

        assertThat(assertLegacyStored("cloudsitter_policies", policy.getId(), "schedule_json", "{\"v\":2}"))
                .isNotEqualTo(policyRef);
        assertThat(assertLegacyStored("github_integration_config", config.getId(), "encrypted_pat", "second-pat"))
                .isNotEqualTo(patRef);
        assertThat(assertLegacyStored("sonarqube_project", project.getId(), "encrypted_token", "second-token"))
                .isNotEqualTo(tokenRef);
        for (String previous : List.of(policyRef, patRef, tokenRef)) {
            assertThat(PgTestSupport.loExists(url, Long.parseLong(previous))).as("previous object %s kept", previous).isTrue();
        }
        assertThat(PgTestSupport.loCount(url)).isEqualTo(objects + 6);
    }

    // ------------------------------------------------------------------ helpers

    /** Asserts the column holds OID text whose object reads back as {@code value}; returns the OID text. */
    private static String assertLegacyStored(String table, Object id, String column, String value) {
        String ref = raw(table, id, column);
        assertThat(LegacyLobTextType.isCandidate(ref)).as("%s.%s holds an OID: %s", table, column, ref).isTrue();
        assertThat(PgTestSupport.loText(url, Long.parseLong(ref))).as("convert_from(lo_get(%s))", ref).isEqualTo(value);
        assertThat(PgTestSupport.clobText(url, table, "id", id, column)).as("getClob, the August path").isEqualTo(value);
        return ref;
    }

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

    private static String raw(String table, Object id, String column) {
        return PgTestSupport.rawText(url, table, "id", id, column);
    }

    private static Instant lastSyncedAt(Long id) {
        return Instant.ofEpochSecond(PgTestSupport.queryLong(url,
                "SELECT extract(epoch FROM last_synced_at)::bigint FROM github_integration_config WHERE id = ?", id));
    }
}
