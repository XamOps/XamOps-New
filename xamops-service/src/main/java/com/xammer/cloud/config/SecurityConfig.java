package com.xammer.cloud.config;

import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import java.util.Arrays;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity(debug = false)
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

        private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;
        private final CustomAuthenticationFailureHandler authenticationFailureHandler;
        private final CustomLogoutSuccessHandler logoutSuccessHandler;
        private final UserDetailsService userDetailsService;

        public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler,
                        CustomAuthenticationFailureHandler authenticationFailureHandler,
                        CustomLogoutSuccessHandler logoutSuccessHandler,
                        UserDetailsService userDetailsService) {
                this.authenticationSuccessHandler = authenticationSuccessHandler;
                this.authenticationFailureHandler = authenticationFailureHandler;
                this.logoutSuccessHandler = logoutSuccessHandler;
                this.userDetailsService = userDetailsService;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http

                                .headers(headers -> headers
                                                .frameOptions(frameOptions -> frameOptions.disable()))
                                .cors(withDefaults())
                                .csrf(csrf -> csrf.disable())
                                .exceptionHandling(exceptions -> exceptions
                                                .defaultAuthenticationEntryPointFor(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                                                new AntPathRequestMatcher("/api/**")))
                                .authorizeHttpRequests((requests) -> requests
                                                // ✅ TEAM FIX: Allow Password Reset APIs Publicly
                                                .requestMatchers(new AntPathRequestMatcher("/api/auth/**")).permitAll()

                                                // Grafana Ingest
                                                .requestMatchers(new AntPathRequestMatcher(
                                                                "/api/xamops/cloudguard/grafana-ingest", "POST"))
                                                .permitAll()

                                                // EventBridge auto-connect callback — called by Lambda, no session
                                                .requestMatchers(new AntPathRequestMatcher(
                                                                "/api/xamops/account-manager/eventbridge-callback", "POST"))
                                                .permitAll()

                                                // Disk agent install script and binary downloads — public so EC2 instances can download without auth
                                                .requestMatchers(new AntPathRequestMatcher("/agentinstall/agent_install.sh")).permitAll()
                                                .requestMatchers(new AntPathRequestMatcher("/agent/download/**")).permitAll()

                                                // Docker/K8s health checks — must be public so health probes don't 401
                                                .requestMatchers(new AntPathRequestMatcher("/actuator/health")).permitAll()

                                                // Public Static Resources & Pages
                                                .requestMatchers(
                                                                new AntPathRequestMatcher("/"),
                                                                new AntPathRequestMatcher("/index.html"),
                                                                new AntPathRequestMatcher("/login"),
                                                                new AntPathRequestMatcher("/css/**"),
                                                                new AntPathRequestMatcher("/js/**"),
                                                                new AntPathRequestMatcher("/images/**"),
                                                                new AntPathRequestMatcher("/icons/**"),
                                                                new AntPathRequestMatcher("/webjars/**"),

                                                                // Connectivity
                                                                new AntPathRequestMatcher("/ws/**"),
                                                                new AntPathRequestMatcher("/xamops-ws/**"),

                                                                // XamOps Agent API (authenticates via API key, not session)
                                                                new AntPathRequestMatcher("/api/v1/agent/**"),

                                                                // Public APIs
                                                                new AntPathRequestMatcher("/api/cicd/github/runs"))
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .formLogin((form) -> form
                                                .loginPage("/login")
                                                .successHandler(authenticationSuccessHandler)
                                                .failureHandler(authenticationFailureHandler)
                                                .permitAll())
                                .logout((logout) -> logout
                                                .logoutUrl("/logout")
                                                .logoutSuccessHandler(logoutSuccessHandler)
                                                .invalidateHttpSession(true)
                                                .deleteCookies("JSESSIONID", "remember-me")
                                                .permitAll())
                                .rememberMe((rememberMe) -> rememberMe
                                                .key("xamops-remember-me-key-2025")
                                                .tokenValiditySeconds(1209600) // 14 days
                                                .rememberMeParameter("rememberMe")
                                                .userDetailsService(userDetailsService));

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(Arrays.asList(
                                "http://localhost:5173",
                                "https://live.xamops.com",
                                "https://prep.xamops.com",
                                "https://uat.xamops.com",
                                "https://*.ngrok-free.dev",
                                "https://*.ngrok.io"));
                configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                configuration.setAllowedHeaders(Arrays.asList("*"));
                configuration.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }

        @Bean
        public CommonsRequestLoggingFilter requestLoggingFilter() {
                CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
                loggingFilter.setIncludeQueryString(true);
                loggingFilter.setIncludePayload(true);
                loggingFilter.setMaxPayloadLength(10000);
                loggingFilter.setIncludeHeaders(true);
                loggingFilter.setIncludeClientInfo(true);
                return loggingFilter;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public HttpFirewall allowSemicolonHttpFirewall() {
                StrictHttpFirewall firewall = new StrictHttpFirewall();
                firewall.setAllowSemicolon(true);
                return firewall;
        }
}