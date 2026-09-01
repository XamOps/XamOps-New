package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.internal.PersistenceUnitInfoDescriptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parses every hand-written JPQL query on the call repositories.
 *
 * These queries are the one part of the call feature with no compile-time safety
 * net: a malformed JPQL string, a renamed field, or a mistyped enum path compiles
 * perfectly and then fails when Hibernate parses it during context startup. The
 * only full-context test in this module is {@code @Disabled} because it needs
 * live PostgreSQL, Redis and Kafka, so nothing in the unit-test phase would have
 * caught it — the failure would land on whoever next started the service.
 *
 * The queries are read off the {@code @Query} annotations by reflection rather
 * than copied here, so this cannot drift out of sync with the repositories it is
 * checking.
 */
class CallRepositoryQueryParseTest {

    private static EntityManagerFactory emf;
    private static EntityManager em;

    @BeforeAll
    static void bootHibernate() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        settings.put("hibernate.connection.driver_class", "org.h2.Driver");
        settings.put("hibernate.connection.url",
                "jdbc:h2:mem:callquerycheck;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        settings.put("hibernate.connection.username", "sa");
        settings.put("hibernate.connection.password", "");
        settings.put("hibernate.hbm2ddl.auto", "create-drop");
        settings.put("hibernate.show_sql", "false");

        // Only the call entities. A narrow persistence unit keeps this test fast and
        // independent of the rest of the (large) domain model, which is irrelevant to
        // the queries under test.
        emf = new EntityManagerFactoryBuilderImpl(
                new PersistenceUnitInfoDescriptor(new CallOnlyPersistenceUnit()), settings)
                .build();
        em = emf.createEntityManager();
    }

    @AfterAll
    static void shutdown() {
        if (em != null) {
            em.close();
        }
        if (emf != null) {
            emf.close();
        }
    }

    @Test
    void everyCallParticipantQueryParses() {
        assertAllQueriesParse(CallParticipantRepository.class);
    }

    @Test
    void everyCallSessionQueryParses() {
        assertAllQueriesParse(CallSessionRepository.class);
    }

    private void assertAllQueriesParse(Class<?> repository) {
        List<String> queries = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            Query annotation = method.getAnnotation(Query.class);
            if (annotation != null && !annotation.value().isBlank()) {
                queries.add(annotation.value());
            }
        }

        assertFalse(queries.isEmpty(),
                repository.getSimpleName() + " has no @Query methods — has this test gone stale?");

        for (String jpql : queries) {
            try {
                // createQuery is what forces the JPQL through Hibernate's parser, which is
                // the same validation that happens at context startup.
                em.createQuery(jpql);
            } catch (Exception e) {
                fail("Invalid JPQL on " + repository.getSimpleName() + ":\n  " + jpql
                        + "\n  -> " + e.getMessage());
            }
        }
    }

    /** Minimal persistence unit covering just the call entities. */
    private static class CallOnlyPersistenceUnit implements PersistenceUnitInfo {

        @Override
        public String getPersistenceUnitName() {
            return "call-query-check";
        }

        @Override
        public String getPersistenceProviderClassName() {
            return "org.hibernate.jpa.HibernatePersistenceProvider";
        }

        // Jakarta Persistence 3.2 added CDI scope/qualifier metadata to the SPI. This
        // persistence unit is built by hand rather than by a CDI container, so there is
        // no scope or qualifier to report.
        @Override
        public String getScopeAnnotationName() {
            return null;
        }

        @Override
        public List<String> getQualifierAnnotationNames() {
            return Collections.emptyList();
        }

        @SuppressWarnings("removal") // getTransactionType() is the only accessor the SPI still declares
        @Override
        public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }

        @Override
        public DataSource getJtaDataSource() {
            return null;
        }

        @Override
        public DataSource getNonJtaDataSource() {
            return null;
        }

        @Override
        public List<String> getMappingFileNames() {
            return Collections.emptyList();
        }

        @Override
        public List<URL> getJarFileUrls() {
            return Collections.emptyList();
        }

        @Override
        public URL getPersistenceUnitRootUrl() {
            return null;
        }

        @Override
        public List<String> getManagedClassNames() {
            return List.of(CallSession.class.getName(), CallParticipant.class.getName());
        }

        @Override
        public boolean excludeUnlistedClasses() {
            return true;
        }

        @Override
        public SharedCacheMode getSharedCacheMode() {
            return SharedCacheMode.UNSPECIFIED;
        }

        @Override
        public ValidationMode getValidationMode() {
            return ValidationMode.NONE;
        }

        @Override
        public Properties getProperties() {
            return new Properties();
        }

        @Override
        public String getPersistenceXMLSchemaVersion() {
            return "2.2";
        }

        @Override
        public ClassLoader getClassLoader() {
            return CallRepositoryQueryParseTest.class.getClassLoader();
        }

        @Override
        public void addTransformer(ClassTransformer transformer) {
            // no bytecode enhancement needed for query parsing
        }

        @Override
        public ClassLoader getNewTempClassLoader() {
            return getClassLoader();
        }
    }
}
