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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 10, default configuration: neither the write mode nor the convert-on-read flag is set. Reading a
 * legacy row through {@link CostDataSnapshotService} returns the decoded value and writes nothing:
 * the stored OID text, {@code last_updated} and the large objects stay as they were.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CostDataSnapshotService.class, ConvertOnReadPgTestBase.Jackson.class})
class ConvertOnReadDefaultPgTest extends ConvertOnReadPgTestBase {

    private static final String DATABASE = "lobtext_x_cor_default";

    private static String url;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        url = register(registry, DATABASE, null, null);
    }

    @Override
    String url() {
        return url;
    }

    @Test
    void byDefaultTheServiceRunsInLegacyModeWithConvertOnReadOff() {
        assertServiceSees(LobTextWriteMode.LEGACY, false);
    }

    @Test
    void byDefaultAReadReturnsTheDecodedValueAndLeavesTheRowUnchanged() {
        assertReadLeavesTheRowUntouched("100000000001");
    }

    /** The default flag also holds after the cutover to inline writes (runbook step C3). */
    @Test
    void withTheFlagAtItsDefaultAnInlineModeReadAlsoWritesNothing() {
        Object target = AopTestUtils.getTargetObject(snapshotService);
        ReflectionTestUtils.setField(target, "writeMode", LobTextWriteMode.INLINE);
        try {
            assertServiceSees(LobTextWriteMode.INLINE, false);
            assertReadLeavesTheRowUntouched("100000000002");
        } finally {
            ReflectionTestUtils.setField(target, "writeMode", LobTextWriteMode.LEGACY);
        }
    }
}
