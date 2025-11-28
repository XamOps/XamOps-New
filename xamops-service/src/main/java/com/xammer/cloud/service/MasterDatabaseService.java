package com.xammer.cloud.service;

import com.xammer.cloud.dto.GlobalUserDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Optional;

@Service
public class MasterDatabaseService {

    private final JdbcTemplate jdbcTemplate;

    // Inject the specific 'masterDataSource' bean we created in Config
    public MasterDatabaseService(@Qualifier("masterDataSource") DataSource masterDataSource) {
        this.jdbcTemplate = new JdbcTemplate(masterDataSource);
    }

    /**
     * Looks up a user in the Global Directory (Master DB).
     */
    public Optional<GlobalUserDto> findGlobalUser(String username) {
        try {
            String sql = "SELECT username, tenant_id, role, enabled FROM global_users WHERE username = ?";
            
            GlobalUserDto user = jdbcTemplate.queryForObject(sql, new Object[]{username}, 
                (rs, rowNum) -> new GlobalUserDto(
                    rs.getString("username"),
                    rs.getString("tenant_id"),
                    rs.getString("role"),
                    rs.getBoolean("enabled")
                ));
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Registers a new user into the Global Directory (Master DB).
     * Call this when creating a new user in any tenant.
     */
    public void registerGlobalUser(String username, String passwordHash, String email, String role, String tenantId) {
        String sql = "INSERT INTO global_users (username, password, email, role, tenant_id, enabled) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, username, passwordHash, email, role, tenantId, true);
    }
}