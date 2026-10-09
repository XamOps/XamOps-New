package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.CustomType;
import org.hibernate.type.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mode wiring on the H2 slice (runs in CI): each of the four attributes is mapped to a
 * {@link LegacyLobTextType} instance, and with no write-mode setting every instance, and
 * {@link LobTextWriteMode#effective}, is {@code LEGACY}. The test {@code application.properties}
 * shadows the main one, so no bridge line is present here.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class LobTextModeWiringTest {

    /** The guarded attributes, by entity. */
    static final Map<Class<?>, String> GUARDED_ATTRIBUTES = new LinkedHashMap<>();

    static {
        GUARDED_ATTRIBUTES.put(CachedData.class, "jsonData");
        GUARDED_ATTRIBUTES.put(CloudsitterPolicy.class, "scheduleJson");
        GUARDED_ATTRIBUTES.put(GitHubIntegrationConfig.class, "encryptedPat");
        GUARDED_ATTRIBUTES.put(SonarQubeProject.class, "encryptedToken");
    }

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private EntityManager entityManager;

    @Test
    void everyGuardedAttributeUsesLegacyModeByDefault() {
        assertWriteMode(emf, LobTextWriteMode.LEGACY);
    }

    @Test
    void otherDialectsStoreAndReadTheValueUnchanged() {
        entityManager.persist(new CachedData("wiring::h2", "16383", LocalDateTime.of(2026, 10, 1, 12, 0)));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.createNativeQuery(
                "SELECT CAST(json_data AS VARCHAR(64)) FROM cached_data WHERE cache_key = 'wiring::h2'")
                .getSingleResult()).isEqualTo("16383");
        assertThat(entityManager.find(CachedData.class, "wiring::h2").getJsonData()).isEqualTo("16383");
    }

    /** Asserts the mapped type of each guarded attribute, and the effective mode, equal {@code expected}. */
    static void assertWriteMode(EntityManagerFactory emf, LobTextWriteMode expected) {
        SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
        GUARDED_ATTRIBUTES.forEach((entity, attribute) -> {
            Type type = sf.getMappingMetamodel().getEntityDescriptor(entity).getPropertyType(attribute);
            assertThat(type).as("%s.%s", entity.getSimpleName(), attribute).isInstanceOf(CustomType.class);
            Object userType = ((CustomType<?>) type).getUserType();
            assertThat(userType).as("%s.%s", entity.getSimpleName(), attribute).isInstanceOf(LegacyLobTextType.class);
            assertThat(((LegacyLobTextType) userType).writeMode()).as("%s.%s", entity.getSimpleName(), attribute)
                    .isEqualTo(expected);
        });
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(expected);
    }
}
