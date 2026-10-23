package com.xammer.cloud.config;

import com.xammer.cloud.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.cloud.security.ActivityLogoutHandler;
import com.xammer.cloud.security.CustomAuthenticationFailureHandler;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import java.util.Arrays;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity(debug = false)
// Spring Security 7 dropped @EnableGlobalMethodSecurity; @EnableMethodSecurity turns on
// pre/post annotations by default, which is what prePostEnabled=true used to do.
@EnableMethodSecurity
public class SecurityConfig {

        /**
         * Security 7 removed AntPathRequestMatcher. Every rule below was an Ant pattern
         * over a servlet path, which PathPattern parses identically for the shapes used
         * here ({@code /a/**}, {@code /a/*.html}, literals).
         */
        private static final PathPatternRequestMatcher.Builder PATH = PathPatternRequestMatcher.withDefaults();

        private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;
        private final CustomAuthenticationFailureHandler authenticationFailureHandler;
        private final CustomLogoutSuccessHandler logoutSuccessHandler;
        private final UserDetailsService userDetailsService;

        /**
         * Records a logout event, when activity tracking is on the context.
         *
         * An {@link ObjectProvider} rather than a hard dependency because this is audit, not
         * authentication: if it is absent, signing out still works and the only loss is a row
         * in {@code user_activity_event}. Requiring it would also drag
         * {@code UserActivityService} and two repositories into every slice test that imports
         * this config to exercise the filter chain -- three of them do -- none of which care
         * about telemetry.
         */
        private final ObjectProvider<ActivityLogoutHandler> activityLogoutHandler;

        @Autowired
        private ImpersonationJwtFilter impersonationJwtFilter;

        @Autowired
        private com.xammer.cloud.security.TrialEnforcementFilter trialEnforcementFilter;

        /**
         * Signing key for the remember-me cookie. No default: it must be supplied per
         * environment rather than falling back to a value in source.
         */
        @org.springframework.beans.factory.annotation.Value("${app.remember-me.key}")
        private String rememberMeKey;

        public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler,
                        CustomAuthenticationFailureHandler authenticationFailureHandler,
                        CustomLogoutSuccessHandler logoutSuccessHandler,
                        ObjectProvider<ActivityLogoutHandler> activityLogoutHandler,
                        UserDetailsService userDetailsService) {
                this.authenticationSuccessHandler = authenticationSuccessHandler;
                this.authenticationFailureHandler = authenticationFailureHandler;
                this.logoutSuccessHandler = logoutSuccessHandler;
                this.activityLogoutHandler = activityLogoutHandler;
                this.userDetailsService = userDetailsService;
        }

        /**
         * Handles impersonation requests that carry a billops-issued JWT.
         * STATELESS — SecurityContextPersistenceFilter skips session read/write entirely,
         * so the admin's JSESSIONID is never touched by these requests.
         */
        @Bean
        @Order(1)
        public SecurityFilterChain impersonationJwtFilterChain(HttpSecurity http) throws Exception {
                http
                        // Match requests with Authorization header BUT exclude /api/llm-proxy/**
                        // which uses X-XamOps-Key for auth — the Authorization header there carries
                        // the provider API key, not an impersonation JWT.
                        .securityMatcher(request -> {
                            String path = request.getRequestURI();
                            String auth = request.getHeader("Authorization");
                            return auth != null
                                    && !path.startsWith("/api/llm-proxy/")
                                    && !path.startsWith("/api/v1/agent/");
                        })
                        .cors(withDefaults())
                        .csrf(csrf -> csrf.disable())
                        .sessionManagement(session -> session
                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                        .exceptionHandling(ex -> ex
                                .defaultAuthenticationEntryPointFor(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                        PATH.matcher("/api/**")))
                        .authorizeHttpRequests(auth -> auth
                                .requestMatchers(PATH.matcher("/api/admin/**"))
                                    .hasAuthority("ROLE_SUPER_ADMIN")
                                .anyRequest().authenticated())
                        .addFilterBefore(impersonationJwtFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http

                                .headers(headers -> headers
                                                // Was frameOptions().disable(), i.e. this app could be framed by
                                                // any site. With cookie-based sessions that is a clickjacking
                                                // primitive: an attacker frames a real, logged-in page and
                                                // overlays their own UI to harvest clicks. SAMEORIGIN keeps the
                                                // app's own iframes (the embedded Grafana dashboards) working
                                                // while blocking third-party framing.
                                                .frameOptions(frameOptions -> frameOptions.sameOrigin())

                                                // No CSP existed at all, which is what let the stored XSS in
                                                // ticket replies run with nothing in its way.
                                                //
                                                // 'unsafe-inline'/'unsafe-eval' are present for script-src
                                                // because the legacy static pages under /js and the CDN
                                                // <script> tags in index.html rely on them; removing those is
                                                // a frontend change, not a config change. So this is a
                                                // mitigation, not a fix — the sanitiser on the ticket path is
                                                // what actually stops the payload. What CSP does buy here is
                                                // object-src 'none', base-uri 'self' and frame-ancestors
                                                // 'self', which close off plugin injection, <base> hijacking
                                                // and framing regardless of the script policy.
                                                .contentSecurityPolicy(csp -> csp.policyDirectives(
                                                                "default-src 'self'; "
                                                                + "script-src 'self' 'unsafe-inline' 'unsafe-eval' https:; "
                                                                + "style-src 'self' 'unsafe-inline' https:; "
                                                                + "img-src 'self' data: blob: https:; "
                                                                + "font-src 'self' data: https:; "
                                                                + "connect-src 'self' https: wss:; "
                                                                + "object-src 'none'; "
                                                                + "base-uri 'self'; "
                                                                + "frame-ancestors 'self'"))
                                                .referrerPolicy(referrer -> referrer.policy(
                                                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                                .cors(withDefaults())
                                // NOTE: CSRF remains disabled. Enabling it is a coordinated
                                // frontend change (every mutating fetch needs to carry the token)
                                // and turning it on here alone would break every POST/PUT/DELETE
                                // in the product. Tracked as a follow-up rather than silently
                                // shipped half-done.
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionFixation().changeSessionId())
                                .exceptionHandling(exceptions -> exceptions
                                                .defaultAuthenticationEntryPointFor(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                                                PATH.matcher("/api/**")))
                                .authorizeHttpRequests((requests) -> requests
                                                // ✅ TEAM FIX: Allow Password Reset APIs Publicly
                                                .requestMatchers(PATH.matcher("/api/auth/**")).permitAll()

                                                // NOTE: a permitAll entry for
                                                // /api/xamops/cloudguard/grafana-ingest was removed here. No
                                                // controller maps that path, so the rule was config drift. It
                                                // was harmless today but would have silently published the
                                                // endpoint the moment someone added a handler with that name.

                                                // EventBridge auto-connect callback — called by Lambda, no session
                                                .requestMatchers(PATH.matcher(HttpMethod.POST, "/api/xamops/account-manager/eventbridge-callback"))
                                                .permitAll()

                                                // Upwind real-time detection webhook — public, authenticated by
                                                // shared secret (X-Upwind-Secret header), not a session
                                                .requestMatchers(PATH.matcher(
                                                                "/api/xamsecure/webhook/**"))
                                                .permitAll()

                                                // Disk agent install scripts and binary downloads — public so instances can download without auth
                                                .requestMatchers(PATH.matcher("/agentinstall/agent_install.sh")).permitAll()
                                                .requestMatchers(PATH.matcher("/agentinstall/gcp_disk_agent_install.sh")).permitAll()
                                                .requestMatchers(PATH.matcher("/agent/download/**")).permitAll()

                                                // Docker/K8s health checks — must be public so health probes don't 401
                                                .requestMatchers(PATH.matcher("/actuator/health")).permitAll()

                                                // Maintenance status — must be public so frontend can check even when unauthenticated
                                                .requestMatchers(PATH.matcher("/api/health")).permitAll()
                                                .requestMatchers(PATH.matcher("/api/maintenance/status")).permitAll()

                                                // Public Static Resources & Pages
                                                .requestMatchers(
                                                                PATH.matcher("/"),
                                                                PATH.matcher("/index.html"),
                                                                PATH.matcher("/login"),
                                                                PATH.matcher("/css/**"),
                                                                PATH.matcher("/js/**"),
                                                                PATH.matcher("/images/**"),
                                                                PATH.matcher("/icons/**"),
                                                                PATH.matcher("/webjars/**"),
                                                                PATH.matcher("/gcp_*.html"),
                                                                PATH.matcher("/azure_dashboard.html"),
                                                                PATH.matcher("/azure_*.html"),
                                                                PATH.matcher("/cloudlist.html"),
                                                                PATH.matcher("/cloudk8s.html"),
                                                                PATH.matcher("/eks-details.html"),
                                                                PATH.matcher("/sonarqube.html"),
                                                                PATH.matcher("/user-manager.html"),
                                                                PATH.matcher("/spot-automation.html"),
                                                                PATH.matcher("/superadmin_customer.html"),
                                                                PATH.matcher("/my-profile.html"),
                                                                // ✅ YOUR CODE: Preserved missing pages
                                                                PATH.matcher("/cloudsitter.html"),
                                                                PATH.matcher("/integration-guide.html"),
                                                                // NOTE: "actions-history.html",
                                                                // "billops/dashboard_user.html" and
                                                                // "billops/dashboard_admin.html" were listed here without a
                                                                // leading slash. AntPathRequestMatcher compares against the
                                                                // servlet path, which always starts with "/", so none of the
                                                                // three ever matched a request — they were dead rules.
                                                                // PathPattern rejects relative patterns outright, so they
                                                                // are dropped rather than silently turned into three newly
                                                                // public pages as part of an upgrade.

                                                                // Connectivity
                                                                PATH.matcher("/ws/**"),
                                                                PATH.matcher("/xamops-ws/**"),
                                                                PATH.matcher("/unified-dashboard"),
                                                                PATH.matcher("/dashboard"),
                                                                PATH.matcher("/sonarqube"),
                                                                PATH.matcher("/cloudlist"),
                                                                PATH.matcher("/finops"),
                                                                PATH.matcher("/cost"),
                                                                PATH.matcher("/waste"),
                                                                PATH.matcher("/rightsizing"),
                                                                PATH.matcher("/reservation"),
                                                                PATH.matcher("/cloudsitter"),
                                                                PATH.matcher("/spot-automation"),
                                                                PATH.matcher("/flex-compute"),
                                                                PATH.matcher("/account-manager"),
                                                                PATH.matcher("/security"),
                                                                PATH.matcher("/performance"),
                                                                PATH.matcher("/alerts"),
                                                                PATH.matcher("/tickets"),
                                                                PATH.matcher("/ticket-detail"),
                                                                PATH.matcher("/cloudshell"),
                                                                PATH.matcher("/add-account"),
                                                                PATH.matcher("/add-gcp-account"),
                                                                PATH.matcher("/add-github-config"),
                                                                PATH.matcher("/devops-in-the-box"),
                                                                PATH.matcher("/cicd-pipelines"),
                                                                PATH.matcher("/grafana-dashboard"),
                                                                PATH.matcher("/cloudk8s"),
                                                                PATH.matcher("/eks-details"),
                                                                PATH.matcher("/gcp-dashboard"),
                                                                PATH.matcher("/gcp-finops"),
                                                                PATH.matcher("/gcp-cost"),
                                                                PATH.matcher("/gcp-waste"),
                                                                PATH.matcher("/gcp-rightsizing"),
                                                                PATH.matcher("/gcp-reservations"),
                                                                PATH.matcher("/gcp-cloudk8s"),
                                                                PATH.matcher("/gcp-security"),
                                                                PATH.matcher("/gcp-performance"),
                                                                PATH.matcher("/gcp-alerts"),

                                                                // NOTE: /terminal and /terminal/** were here. That is the
                                                                // CloudShell WebSocket, not a page route — leaving it public
                                                                // meant an unauthenticated handshake could open an
                                                                // interactive shell holding a customer's cloud credentials.
                                                                // It is now in the authenticated block below, which is what
                                                                // populates the principal the handler checks ownership with.

                                                                // XamOps Agent API (authenticates via API key, not session)
                                                                PATH.matcher("/api/v1/agent/**"),

                                                                // XamOps Metering API — public:
                                                                //   /register → AWS Marketplace redirect (no session)
                                                                //   /events   → ISV product server (API key auth in service layer)
                                                                //   /sns      → AWS SNS webhook (no session, AWS calls directly)
                                                                //   /health   → uptime check
                                                                PATH.matcher(HttpMethod.POST, "/api/metering/register"),
                                                                PATH.matcher(HttpMethod.POST, "/api/metering/events"),
                                                                PATH.matcher(HttpMethod.POST, "/api/metering/events/batch"),
                                                                PATH.matcher("/api/metering/customers/**"),
                                                                PATH.matcher(HttpMethod.POST, "/api/metering/sns"),
                                                                PATH.matcher("/api/metering/health"),

                                                                // Public APIs
                                                                // PATH.matcher("/api/ai/**"),
                                                                // ✅ TEAM CODE: Preserved AWS Calculator API
                                                                // Pricing lookups only — no account data, no customer
                                                                // credentials, nothing tenant-scoped behind it.
                                                                PATH.matcher("/api/aws/**"),

                                                                // LLM Proxy — user apps call this with provider API key, not session
                                                                PATH.matcher("/api/llm-proxy/**"))
                                                .permitAll()

                                                // ── Previously permitAll, now authenticated ──────────────
                                                //
                                                // These were public and tenant-scoped at the same time, which
                                                // is the combination that made the AutoSpotting IDOR trivial:
                                                // every handler takes an account identifier and did nothing to
                                                // check it, so an unauthenticated caller could drive spot
                                                // infrastructure in any customer's cloud account by walking a
                                                // sequential integer. The controllers now enforce ownership via
                                                // CloudAccountAccessService, and authentication is required here
                                                // so that enforcement has a principal to work with.
                                                //
                                                // Session auth is correct for all of these: they are called by
                                                // the frontend on behalf of a logged-in user, not by any cloud
                                                // provider or agent. The genuinely machine-called endpoints
                                                // (agent API, metering, marketplace SNS, install scripts) stay
                                                // in the permitAll block above with their own key/signature
                                                // checks.
                                                .requestMatchers(
                                                                PATH.matcher("/terminal"),
                                                                PATH.matcher("/terminal/**"),
                                                                PATH.matcher("/api/autospotting/**"),
                                                                PATH.matcher("/api/gcp/autospotting/**"),
                                                                PATH.matcher("/api/flexcompute/**"),
                                                                PATH.matcher("/api/dev_ops_script/**"),
                                                                PATH.matcher("/api/cicd/**"),
                                                                PATH.matcher("/api/xamops/k8s/**"),
                                                                PATH.matcher("/api/xamops/eks/**"),
                                                                PATH.matcher("/api/eks/autoscaling/**"),
                                                                PATH.matcher("/api/karpenter/**"))
                                                .authenticated()

                                                .anyRequest().authenticated())
                                .formLogin((form) -> form
                                                .loginPage("/login")
                                                .successHandler(authenticationSuccessHandler)
                                                .failureHandler(authenticationFailureHandler)
                                                .permitAll())
                                .logout((logout) -> {
                                        logout
                                                .logoutUrl("/logout")
                                                .logoutSuccessHandler(logoutSuccessHandler)
                                                .invalidateHttpSession(true)
                                                .deleteCookies("JSESSIONID", "remember-me")
                                                .permitAll();
                                        // Added before Spring's own SecurityContextLogoutHandler,
                                        // which is what performs invalidateHttpSession(true).
                                        // Handlers registered here run first, so the session still
                                        // exists and the event can carry the id that pairs it with
                                        // the matching login. A logoutSuccessHandler runs after
                                        // invalidation and would have nothing left to read.
                                        ActivityLogoutHandler activityHandler =
                                                        activityLogoutHandler.getIfAvailable();
                                        if (activityHandler != null) {
                                                logout.addLogoutHandler(activityHandler);
                                        }
                                })
                                .rememberMe((rememberMe) -> rememberMe
                                                // Was the literal "xamops-remember-me-key-2025". This key
                                                // signs the remember-me cookie, so publishing it in source
                                                // removes half of what an attacker needs to mint a valid
                                                // persistent-login token — the rest being a username, an
                                                // expiry and the account's bcrypt hash. Low entropy and
                                                // committed to git is the worst combination for a signing
                                                // key, even one that is not sufficient on its own.
                                                //
                                                // Rotating this invalidates existing remember-me cookies, so
                                                // users with an active "remember me" session sign in once more.
                                                .key(rememberMeKey)
                                                .tokenValiditySeconds(1209600) // 14 days
                                                .rememberMeParameter("rememberMe")
                                                .userDetailsService(userDetailsService));

                // Enforce trial expiry after authentication so an expired client
                // cannot reach protected APIs (runs for /api/** except /api/auth/**).
                http.addFilterAfter(trialEnforcementFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(Arrays.asList(
                                "http://localhost:5173",
                                "https://live.xamops.com",
                                "https://demo.xamops.com",
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
                loggingFilter.setIncludePayload(false);
                loggingFilter.setIncludeHeaders(false);
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