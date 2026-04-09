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
                                                                new AntPathRequestMatcher("/gcp_*.html"),
                                                                new AntPathRequestMatcher("/azure_dashboard.html"),
                                                                new AntPathRequestMatcher("/azure_*.html"),
                                                                new AntPathRequestMatcher("/cloudlist.html"),
                                                                new AntPathRequestMatcher("/cloudk8s.html"),
                                                                new AntPathRequestMatcher("/eks-details.html"),
                                                                new AntPathRequestMatcher("/sonarqube.html"),
                                                                new AntPathRequestMatcher("/user-manager.html"),
                                                                new AntPathRequestMatcher("/spot-automation.html"),
                                                                new AntPathRequestMatcher("/superadmin_customer.html"),
                                                                new AntPathRequestMatcher("actions-history.html"),
                                                                new AntPathRequestMatcher("/my-profile.html"),
                                                                // ✅ YOUR CODE: Preserved missing pages
                                                                new AntPathRequestMatcher("/cloudsitter.html"),
                                                                new AntPathRequestMatcher("/integration-guide.html"),
                                                                new AntPathRequestMatcher(
                                                                                "billops/dashboard_user.html"),
                                                                new AntPathRequestMatcher(
                                                                                "billops/dashboard_admin.html"),
                                                                // new
                                                                // AntPathRequestMatcher("billops/support-dashboard.html"),

                                                                // Connectivity
                                                                new AntPathRequestMatcher("/ws/**"),
                                                                new AntPathRequestMatcher("/xamops-ws/**"),
                                                                new AntPathRequestMatcher("/unified-dashboard"),
                                                                new AntPathRequestMatcher("/dashboard"),
                                                                new AntPathRequestMatcher("/sonarqube"),
                                                                new AntPathRequestMatcher("/cloudlist"),
                                                                new AntPathRequestMatcher("/finops"),
                                                                new AntPathRequestMatcher("/cost"),
                                                                new AntPathRequestMatcher("/waste"),
                                                                new AntPathRequestMatcher("/rightsizing"),
                                                                new AntPathRequestMatcher("/reservation"),
                                                                new AntPathRequestMatcher("/cloudsitter"),
                                                                new AntPathRequestMatcher("/spot-automation"),
                                                                new AntPathRequestMatcher("/flex-compute"),
                                                                new AntPathRequestMatcher("/account-manager"),
                                                                new AntPathRequestMatcher("/security"),
                                                                new AntPathRequestMatcher("/performance"),
                                                                new AntPathRequestMatcher("/alerts"),
                                                                new AntPathRequestMatcher("/tickets"),
                                                                new AntPathRequestMatcher("/ticket-detail"),
                                                                new AntPathRequestMatcher("/cloudshell"),
                                                                new AntPathRequestMatcher("/add-account"),
                                                                new AntPathRequestMatcher("/add-gcp-account"),
                                                                new AntPathRequestMatcher("/add-github-config"),
                                                                new AntPathRequestMatcher("/devops-in-the-box"),
                                                                new AntPathRequestMatcher("/cicd-pipelines"),
                                                                new AntPathRequestMatcher("/grafana-dashboard"),
                                                                new AntPathRequestMatcher("/cloudk8s"),
                                                                new AntPathRequestMatcher("/eks-details"),
                                                                new AntPathRequestMatcher("/terminal/**"),
                                                                new AntPathRequestMatcher("/gcp-dashboard"),
                                                                new AntPathRequestMatcher("/gcp-finops"),
                                                                new AntPathRequestMatcher("/gcp-cost"),
                                                                new AntPathRequestMatcher("/gcp-waste"),
                                                                new AntPathRequestMatcher("/gcp-rightsizing"),
                                                                new AntPathRequestMatcher("/gcp-reservations"),
                                                                new AntPathRequestMatcher("/gcp-cloudk8s"),
                                                                new AntPathRequestMatcher("/gcp-security"),
                                                                new AntPathRequestMatcher("/gcp-performance"),
                                                                new AntPathRequestMatcher("/gcp-alerts"),
                                                                new AntPathRequestMatcher("/terminal"),

                                                                // XamOps Agent API (authenticates via API key, not session)
                                                                new AntPathRequestMatcher("/api/v1/agent/**"),

                                                                // XamOps Metering API — public:
                                                                //   /register → AWS Marketplace redirect (no session)
                                                                //   /events   → ISV product server (API key auth in service layer)
                                                                //   /sns      → AWS SNS webhook (no session, AWS calls directly)
                                                                //   /health   → uptime check
                                                                new AntPathRequestMatcher("/api/metering/register", "POST"),
                                                                new AntPathRequestMatcher("/api/metering/events", "POST"),
                                                                new AntPathRequestMatcher("/api/metering/events/batch", "POST"),
                                                                new AntPathRequestMatcher("/api/metering/customers/**"),
                                                                new AntPathRequestMatcher("/api/metering/sns", "POST"),
                                                                new AntPathRequestMatcher("/api/metering/health"),

                                                                // Public APIs
                                                                // new AntPathRequestMatcher("/api/ai/**"),
                                                                new AntPathRequestMatcher("/api/cicd/github/runs"),
                                                                new AntPathRequestMatcher("/api/cicd/config/**"),
                                                                new AntPathRequestMatcher("/api/cicd/**"),
                                                                new AntPathRequestMatcher("/api/xamops/k8s/**"),
                                                                // ✅ YOUR CODE: Preserved missing APIs
                                                                new AntPathRequestMatcher("/api/xamops/eks/**"),
                                                                new AntPathRequestMatcher("/api/karpenter/**"),

                                                                new AntPathRequestMatcher("/api/autospotting/**"),
                                                                new AntPathRequestMatcher("/api/azure/autospotting/**"),
                                                                new AntPathRequestMatcher("/api/gcp/autospotting/**"),
                                                                new AntPathRequestMatcher("/api/flexcompute/**"),
                                                                new AntPathRequestMatcher("/api/dev_ops_script/**"),
                                                                // ✅ TEAM CODE: Preserved AWS Calculator API
                                                                new AntPathRequestMatcher("/api/aws/**"),

                                                                // LLM Proxy — user apps call this with provider API key, not session
                                                                new AntPathRequestMatcher("/api/llm-proxy/**"))
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
                configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
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