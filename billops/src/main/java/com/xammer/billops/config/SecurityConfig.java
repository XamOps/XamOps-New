package com.xammer.billops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // Enable method-level security
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        // Secure the admin invoice endpoints
                        .requestMatchers("/api/admin/invoices/**").hasRole("BILLOPS_ADMIN")
                        // Allow all other requests for now, as per original logic
                        .anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable());  // Disable CSRF (for development only)
        return http.build();
    }
}