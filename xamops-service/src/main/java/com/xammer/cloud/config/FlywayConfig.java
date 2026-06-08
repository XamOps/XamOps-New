package com.xammer.cloud.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Manual Flyway configuration to bypass Spring Boot 2.7.x autoconfiguration,
 * which calls FluentConfiguration.oracleSqlplus() — removed in Flyway 9.x Community.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Value("${spring.flyway.out-of-order:false}")
    private boolean outOfOrder;

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .outOfOrder(outOfOrder)
                .baselineOnMigrate(false)
                .load();
    }
}
