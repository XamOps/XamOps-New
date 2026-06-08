package com.xammer.cloud.repository;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Minimal Spring Boot configuration for @DataJpaTest slice tests.
 *
 * The main application class (CloudDashboardApplication) declares an explicit
 * {@code @ComponentScan(basePackages = "com.xammer.cloud")}, which forces a FULL
 * component scan even inside @DataJpaTest slice tests. That pulls in unrelated
 * beans (WebSocket config, cloud service beans) that fail to wire without the
 * full application context.
 *
 * By pointing repository slice tests at this dedicated @SpringBootConfiguration
 * via @ContextConfiguration, the slice loads ONLY JPA entities and repositories.
 *
 * Note: @DataJpaTest itself enables JPA repository scanning (in this config's
 * package, com.xammer.cloud.repository) and Hibernate/embedded-DB auto-config,
 * so we must NOT declare @EnableJpaRepositories here — only @EntityScan is needed
 * to point at the domain (entity) package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "com.xammer.cloud.domain")
public class JpaSliceTestConfig {
}
