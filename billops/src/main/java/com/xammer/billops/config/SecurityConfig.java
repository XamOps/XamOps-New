package com.xammer.billops.config;

// Import these new classes
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
// We don't need the separate CorsFilter bean
// import org.springframework.web.filter.CorsFilter; 

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // --- ADD THIS ---
    // Inject the origins list from your application.properties
    @Value("${cors.allowed-origins:http://localhost:5173,https://live.xamops.com}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // This is correct
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/billops/users/**").permitAll() 
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    // --- This bean is redundant and can be deleted ---
    // The securityFilterChain uses the 'corsConfigurationSource' bean,
    // not this one.
    /*
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://localhost:5173"); 
        config.addAllowedMethod("*"); 
        config.addAllowedHeader("*"); 
        config.setAllowCredentials(true); 
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
    */

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // --- UPDATE THIS ---
        // Use the injected list from properties instead of a hardcoded value
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        
        configuration.addAllowedMethod("*");
        configuration.addAllowedHeader("*");
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); 
    }
}