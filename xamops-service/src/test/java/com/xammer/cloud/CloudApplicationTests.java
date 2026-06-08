package com.xammer.cloud;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full application-context smoke test.
 *
 * DISABLED in the standard unit-test run because loading the complete Spring
 * context requires live infrastructure that isn't available in CI:
 *   - PostgreSQL (JPA repositories — e.g. CloudShellSocketHandler needs CloudAccountRepository)
 *   - Redis (session + caching)
 *   - Kafka (event consumers)
 *   - Eureka (service discovery)
 *
 * Excluding the datasource auto-config to avoid the DB requirement also removes
 * the JPA repository beans, which other singletons (WebSocket handlers) depend on —
 * so the context cannot start either way without a real database.
 *
 * Run this as an integration test against a provisioned environment
 * (e.g. with Testcontainers) rather than in the unit-test phase.
 */
@SpringBootTest
@Disabled("Full-context smoke test — requires PostgreSQL/Redis/Kafka. Run as an integration test, not in the unit-test phase.")
class CloudApplicationTests {

	@Test
	void contextLoads() {
	}

}
