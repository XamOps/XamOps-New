package com.xammer.billops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // This is still useful for method-level checks if needed
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        // In a microservice architecture behind a gateway,
                        // we trust the gateway to handle authentication.
                        // This service will permit all requests that reach it.
                        .anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable());  // Disable CSRF as it's not needed for this API service
        return http.build();
    }
}