package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The v2 spine entities match the migrated columns exactly (plan-phase-03.md task 3.2): a Hibernate
 * context with {@code ddl-auto=validate} over {@code ..domain.autospotting..}, pointed at a database
 * the spine migration built, starts. Hibernate validate fails startup on any column, type or
 * nullability mismatch, so a clean start proves the mapping.
 */
@Tag("containers")
class SpineSchemaValidateIT {

    @Test
    void validateContextStartsAgainstTheMigratedSchema() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            // The spine tables the entities map to exist.
            assertThat(PgHarness.tableExists(db.jdbc(), "spot_group")).isTrue();

            Map<String, Object> properties = Map.of(
                    "spring.datasource.url", db.jdbcUrl(),
                    "spring.datasource.username", db.username(),
                    "spring.datasource.password", db.password(),
                    "spring.jpa.hibernate.ddl-auto", "validate",
                    "spring.jpa.open-in-view", "false",
                    "spring.flyway.enabled", "false");

            assertThatCode(() -> {
                try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("spine-validate", properties));
                    context.register(SpineEntities.class);
                    context.refresh(); // validate runs on EntityManagerFactory startup
                }
            }).doesNotThrowAnyException();
        }
    }

    /** Spine entities only: no repositories, no Flyway, no application beans. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @EntityScan(basePackages = "com.xammer.cloud.domain.autospotting")
    static class SpineEntities {
    }
}
