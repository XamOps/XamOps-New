package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.LobTextWriteMode;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * Mode wiring on the H2 slice with the Hibernate setting the base-file bridge produces
 * ({@code spring.jpa.properties.xammer.lob-text.write-mode=inline}): every guarded attribute's
 * {@code LegacyLobTextType}, and {@link LobTextWriteMode#effective}, is {@code INLINE}.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.xammer.lob-text.write-mode=inline"
})
class LobTextModeWiringInlineTest {

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void everyGuardedAttributeUsesInlineModeWhenConfigured() {
        LobTextModeWiringTest.assertWriteMode(emf, LobTextWriteMode.INLINE);
    }
}
