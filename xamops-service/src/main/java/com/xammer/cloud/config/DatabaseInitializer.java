package com.xammer.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Database initializer to create tables that are not auto-created by Hibernate
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            logger.info("Initializing database tables...");

            // Create linked_accounts table if it doesn't exist
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS linked_accounts (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    account_id VARCHAR(255) NOT NULL,
                    account_name VARCHAR(255),
                    provider VARCHAR(100),
                    status VARCHAR(50),
                    linked_at TIMESTAMP,
                    synced_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP,
                    CONSTRAINT uk_user_account UNIQUE (user_id, account_id)
                )
                """;

            jdbcTemplate.execute(createTableSql);
            logger.info("✅ Created/verified linked_accounts table");

            // Create index if it doesn't exist
            String createIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_linked_accounts_user_id
                ON linked_accounts(user_id)
                """;

            jdbcTemplate.execute(createIndexSql);
            logger.info("✅ Created/verified index on linked_accounts(user_id)");

        } catch (Exception e) {
            logger.error("❌ Failed to initialize database tables: {}", e.getMessage(), e);
        }
    }
}
