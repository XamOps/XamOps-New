package com.xammer.cloud.config;

// ADD THESE IMPORTS
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
// (end of new imports)

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity(debug = false)
public class SecurityConfig {

    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;

    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults()) // This will now use the corsConfigurationSource() bean
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**")
                        )
                )
                .authorizeHttpRequests((requests) -> requests
                .requestMatchers(new AntPathRequestMatcher("/api/xamops/cloudguard/grafana-ingest", "POST")).permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher("/"),
                                new AntPathRequestMatcher("/index.html"),
                                new AntPathRequestMatcher("/login"),
                                new AntPathRequestMatcher("/css/**"),
                                new AntPathRequestMatcher("/js/**"),
                                new AntPathRequestMatcher("/images/**"),
                                new AntPathRequestMatcher("/icons/**"),
                                new AntPathRequestMatcher("/webjars/**"),
                                new AntPathRequestMatcher("/gcp_*.html"),
                                new AntPathRequestMatcher("/azure_dashboard.html"),
                                new AntPathRequestMatcher("/ws/**"),
                                // --- UPDATED RULES ---
                                // Allow status reads (or use .authenticated())
                                new AntPathRequestMatcher("/api/cicd/github/runs"), 
                                new AntPathRequestMatcher("/api/cicd/config/**"),
                                // All other /api/cicd/ endpoints will be caught by anyRequest().authenticated()
                                // including /api/cicd/config/**
                                // ---
                                new AntPathRequestMatcher("/cloudk8s.html"),
                                new AntPathRequestMatcher("/eks-details.html"),
                                new AntPathRequestMatcher("/api/xamops/k8s/**"),
                                new AntPathRequestMatcher("/sonarqube.html"),
                                new AntPathRequestMatcher("/api/devops-scripts/**")
                                // ** ADD NEW RULE FOR FINOPS SCHEDULES (will be caught by anyRequest().authenticated()) **
                                // No explicit permitAll needed, it should be authenticated.
                        ).permitAll()
                        .anyRequest().authenticated() // <-- This line correctly secures the new endpoints
                )
                .formLogin((form) -> form
                        .loginPage("/login")
                        .successHandler(authenticationSuccessHandler)
                        .permitAll()
                )
                .logout((logout) -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );

        return http.build();
    }
    
    // === FIX: ALLOW PRODUCTION ORIGIN ===
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // FIX APPLIED: Added the production HTTPS domain.
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:5173",
                "https://live.xamops.com" ,
                "https://uat.xamops.com"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        // This is necessary for Spring Security to handle cookies (JSESSIONID)
        configuration.setAllowCredentials(true); 
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); 
        return source;
    }
    // === END OF FIX ===

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
        loggingFilter.setIncludeQueryString(true);
        loggingFilter.setIncludePayload(true);
        loggingFilter.setMaxPayloadLength(10000);
        loggingFilter.setIncludeHeaders(true);
        loggingFilter.setIncludeClientInfo(true);
        loggingFilter.setBeforeMessagePrefix("INCOMING REQUEST DATA: ");
        loggingFilter.setAfterMessagePrefix("REQUEST PROCESSING COMPLETE: ");
        return loggingFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(user -> {
                    Long clientId = Optional.ofNullable(user.getClient())
                            .map(Client::getId)
                            .orElse(null);

                    GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole());
                    List<GrantedAuthority> authorities = Collections.singletonList(authority);

                    return new ClientUserDetails(
                            user.getUsername(),
                            user.getPassword(),
                            authorities,
                            clientId
                    );
                })
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public HttpFirewall allowSemicolonHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        return firewall;
    }
}