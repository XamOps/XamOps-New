// package com.xammer.cloud.config;

// import com.xammer.cloud.domain.Client;
// import com.xammer.cloud.domain.User;
// import com.xammer.cloud.repository.UserRepository;
// import com.xammer.cloud.security.ClientUserDetails;
// import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.http.HttpStatus;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// import org.springframework.security.core.GrantedAuthority;
// import org.springframework.security.core.authority.SimpleGrantedAuthority;
// import org.springframework.security.core.userdetails.UserDetailsService;
// import org.springframework.security.core.userdetails.UsernameNotFoundException;
// import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// import org.springframework.security.crypto.password.PasswordEncoder;
// import org.springframework.security.web.SecurityFilterChain;
// import org.springframework.security.web.authentication.HttpStatusEntryPoint;
// import org.springframework.security.web.firewall.HttpFirewall;
// import org.springframework.security.web.firewall.StrictHttpFirewall;
// import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
// import org.springframework.web.filter.CommonsRequestLoggingFilter;

// import java.util.Collections;
// import java.util.List;
// import java.util.Optional;

// import static org.springframework.security.config.Customizer.withDefaults;

// @Configuration
// @EnableWebSecurity(debug = false)
// public class SecurityConfig {

//     private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;

//     public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler) {
//         this.authenticationSuccessHandler = authenticationSuccessHandler;
//     }

//     @Bean
//     public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//         http
//                 .cors(withDefaults())
//                 .csrf(csrf -> csrf.disable())
//                 .exceptionHandling(exceptions -> exceptions
//                         .defaultAuthenticationEntryPointFor(
//                                 new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
//                                 new AntPathRequestMatcher("/api/**")
//                         )
//                 )
//                 .authorizeHttpRequests((requests) -> requests
//                 .requestMatchers(new AntPathRequestMatcher("/api/xamops/cloudguard/grafana-ingest", "POST")).permitAll()
//                         .requestMatchers(
//                                 new AntPathRequestMatcher("/"),
//                                 new AntPathRequestMatcher("/index.html"),
//                                 new AntPathRequestMatcher("/login"),
//                                 new AntPathRequestMatcher("/css/**"),
//                                 new AntPathRequestMatcher("/js/**"),
//                                 new AntPathRequestMatcher("/images/**"),
//                                 new AntPathRequestMatcher("/icons/**"),
//                                 new AntPathRequestMatcher("/webjars/**"),
//                                 new AntPathRequestMatcher("/gcp_*.html"),
//                                 new AntPathRequestMatcher("/azure_dashboard.html"),
//                                 new AntPathRequestMatcher("/ws/**"),
//                                 new AntPathRequestMatcher("/api/devops-scripts"),

//                                 // --- FIX: ADDED THE FOLLOWING TWO LINES ---
//                                 new AntPathRequestMatcher("/cloudk8s.html"),
//                              new AntPathRequestMatcher("/eks-details.html"),

//                                 new AntPathRequestMatcher("/api/xamops/k8s/**")

//                         ).permitAll()
//                         .anyRequest().authenticated()
//                 )
//                 .formLogin((form) -> form
//                         .loginPage("/login")
//                         .successHandler(authenticationSuccessHandler)
//                         .permitAll()
//                 )
//                 .logout((logout) -> logout
//                         .logoutUrl("/logout")
//                         .logoutSuccessUrl("/login?logout")
//                         .invalidateHttpSession(true)
//                         .deleteCookies("JSESSIONID")
//                         .permitAll()
//                 );

//         return http.build();
//     }

//     @Bean
//     public CommonsRequestLoggingFilter requestLoggingFilter() {
//         CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
//         loggingFilter.setIncludeQueryString(true);
//         loggingFilter.setIncludePayload(true);
//         loggingFilter.setMaxPayloadLength(10000);
//         loggingFilter.setIncludeHeaders(true);
//         loggingFilter.setIncludeClientInfo(true);
//         loggingFilter.setBeforeMessagePrefix("INCOMING REQUEST DATA: ");
//         loggingFilter.setAfterMessagePrefix("REQUEST PROCESSING COMPLETE: ");
//         return loggingFilter;
//     }

//     @Bean
//     public PasswordEncoder passwordEncoder() {
//         return new BCryptPasswordEncoder();
//     }

//     @Bean
//     public UserDetailsService userDetailsService(UserRepository userRepository) {
//         return username -> userRepository.findByUsername(username)
//                 .map(user -> {
//                     Long clientId = Optional.ofNullable(user.getClient())
//                             .map(Client::getId)
//                             .orElse(null);

//                     GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole());
//                     List<GrantedAuthority> authorities = Collections.singletonList(authority);

//                     return new ClientUserDetails(
//                             user.getUsername(),
//                             user.getPassword(),
//                             authorities,
//                             clientId
//                     );
//                 })
//                 .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
//     }

//     @Bean
//     public HttpFirewall allowSemicolonHttpFirewall() {
//         StrictHttpFirewall firewall = new StrictHttpFirewall();
//         firewall.setAllowSemicolon(true);
//         return firewall;
//     }
// }









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
                                // All other /api/cicd/ endpoints will be caught by anyRequest().authenticated()
                                // including /api/cicd/config/**
                                // ---
                                new AntPathRequestMatcher("/cloudk8s.html"),
                                new AntPathRequestMatcher("/eks-details.html"),
                                new AntPathRequestMatcher("/api/xamops/k8s/**"),
                                new AntPathRequestMatcher("/api/devops-scripts/**")
                        ).permitAll()
                        .anyRequest().authenticated()
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
    
    // === ADD THIS NEW BEAN FOR CORS ===
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Set the allowed origin to your frontend's dev server
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:5173"));
        // Set allowed methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Allow all headers
        configuration.setAllowedHeaders(Arrays.asList("*"));
        // **This is the critical line to fix the error**
        configuration.setAllowCredentials(true); 
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Apply this config to all paths
        return source;
    }
    // === END OF NEW BEAN ===

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