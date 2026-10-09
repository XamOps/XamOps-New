package com.xammer.lobtext.billops;

import com.xammer.billops.repository.BillOpsCachedDataRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring configuration for the billops PostgreSQL tests (design §7.1, D16).
 *
 * <p>It lives in {@code com.xammer.lobtext.billops}, outside {@code BillopsApplication}'s
 * component scan ({@code com.xammer.billops}, {@code com.xammer.cloud.permissions},
 * {@code com.xammer.cloud.messaging}), so {@code BillopsApplicationTests.contextLoads} never picks it
 * up. Tests reference it explicitly through {@code @ContextConfiguration} or
 * {@code @SpringBootTest(classes = ...)}, together with {@code @ActiveProfiles("lobtext-pg")}: that
 * replaces the base file's {@code spring.profiles.active=local}, so {@code application-local.properties}
 * and its shared dev database are never loaded. The datasource comes from {@code PgTestSupport}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan({"com.xammer.billops", "com.xammer.cloud.domain"})
@EnableJpaRepositories(basePackageClasses = BillOpsCachedDataRepository.class)
public class LobTextBillopsPgConfig {
}
