package com.xammer.billops.config;

import com.xammer.billops.service.UserDetailsServiceImpl; // ✅ ADD
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus; // ✅ ADD
import org.springframework.security.authentication.dao.DaoAuthenticationProvider; // ✅ ADD
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint; // ✅ ADD
import org.springframework.security.web.util.matcher.AntPathRequestMatcher; // ✅ ADD
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value; // ✅ ADD

import java.util.Arrays; // ✅ ADD

import static org.springframework.security.config.Customizer.withDefaults; // ✅ ADD

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;

    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

    // ✅ ADD THIS BEAN TO CONNECT YOUR UserDetailsServiceImpl
    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsServiceImpl userDetailsService, BCryptPasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }
    // --- ADD THIS ---
    // Inject the origins list from your application.properties
    @Value("${cors.allowed-origins:http://localhost:5173,https://live.xamops.com}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(withDefaults()) // Use the bean below
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")
                )
            )
            .authorizeHttpRequests((requests) -> requests
                .requestMatchers(
                    // Public assets
                    new AntPathRequestMatcher("/"),
                    new AntPathRequestMatcher("/index.html"),
                    new AntPathRequestMatcher("/login"),
                    new AntPathRequestMatcher("/css/**"),
                    new AntPathRequestMatcher("/js/**"),
                    new AntPathRequestMatcher("/icons/**")
                ).permitAll()
                // Secure all other API endpoints
                .requestMatchers(new AntPathRequestMatcher("/api/billops/**")).authenticated()
                .anyRequest().authenticated()
            )
            // ✅ ADD THE COMPLETE formLogin() BLOCK
            .formLogin((form) -> form
                .loginPage("/login") 
                .successHandler(authenticationSuccessHandler)
                .failureHandler((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\": \"Invalid username or password.\"}");
                })
                .permitAll()
            )
            // ✅ ADD THE logout() BLOCK
            .logout((logout) -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }
    
    // This bean is correct, no changes needed
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:5173"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // This bean is correct, no changes needed
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    // ❌ REMOVE the corsFilter() bean. 
    // It conflicts with cors(withDefaults()) and corsConfigurationSource()
    // @Bean
    // public CorsFilter corsFilter() { ... }
}