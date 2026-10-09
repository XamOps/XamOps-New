package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.LobTextWriteMode;
import com.xammer.cloud.service.CostDataSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 10 and FR-2.1: convert-on-read switched on while the write mode is {@code legacy}. The flag is
 * ignored, so a read writes nothing and the row keeps its OID text, {@code last_updated} and object.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CostDataSnapshotService.class, ConvertOnReadPgTestBase.Jackson.class})
class ConvertOnReadLegacyFlagPgTest extends ConvertOnReadPgTestBase {

    private static final String DATABASE = "lobtext_x_cor_legacy_flag";

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        url = register(registry, DATABASE, "legacy", true);
    }

    @Override
    String url() {
        return url;
    }

    @Test
    void theServiceSeesTheFlagOnInLegacyMode() {
        assertServiceSees(LobTextWriteMode.LEGACY, true);
    }

    @Test
    void inLegacyModeTheFlagIsIgnoredAndAReadLeavesTheRowUnchanged() {
        assertReadLeavesTheRowUntouched("200000000001");
    }
}
