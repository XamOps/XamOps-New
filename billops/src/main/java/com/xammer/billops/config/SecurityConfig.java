package com.xammer.billops.config;

import com.xammer.billops.config.multitenancy.ImpersonationJwtFilter;
import com.xammer.billops.security.CustomAuthenticationSuccessHandler;
import com.xammer.billops.security.CustomUserDetailsService; // <--- MUST use this one
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.core.annotation.Order;
import java.util.Arrays;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Security 7 removed AntPathRequestMatcher. Every rule below was an Ant pattern over
     * a servlet path, which PathPattern parses identically for the shapes used here
     * ({@code /a/**}, literals).
     */
    private static final PathPatternRequestMatcher.Builder PATH = PathPatternRequestMatcher.withDefaults();

    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;
    private final CustomUserDetailsService userDetailsService; // Inject this

    @Autowired
    private ImpersonationJwtFilter impersonationJwtFilter;

    @Autowired
    private com.xammer.billops.security.TrialEnforcementFilter trialEnforcementFilter;

    @Value("${cors.allowed-origins:http://localhost:5173,https://live.xamops.com,https://prep.xamops.com,https://uat.xamops.com}")
    private String[] allowedOrigins;

    // Inject CustomUserDetailsService in constructor
    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler,
            CustomUserDetailsService userDetailsService) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(BCryptPasswordEncoder passwordEncoder) {
        // Security 7 dropped the no-arg constructor and setUserDetailsService: the
        // UserDetailsService is now mandatory and supplied up front.
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    /** Completely bypass Spring Security for webhook + OAuth callback endpoints — no auth, no filters */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        // antMatchers() was removed in Security 6; requestMatchers() takes the same
        // patterns and accepts them all in one call.
        return (web) -> web.ignoring()
                .requestMatchers(
                        "/api/billops/webhook/**",
                        "/api/billops/slack/oauth/callback",
                        "/api/billops/teams/app/manifest.zip",
                        "/api/billops/support/files/**");
    }

    /**
     * Handles "Login As" requests carrying a billops-issued JWT in the Authorization header.
     * STATELESS — no JSESSIONID is read or written, so the admin's session is never touched.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain impersonationJwtFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(new RequestHeaderRequestMatcher("Authorization"))
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PATH.matcher("/api/**")))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated())
                .addFilterBefore(impersonationJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests((requests) -> requests
                        // Docker/K8s health checks — must be public so health probes don't 401
                        .requestMatchers(PATH.matcher("/actuator/health")).permitAll()
                        .requestMatchers(
                                PATH.matcher("/"),
                                PATH.matcher("/index.html"),
                                PATH.matcher("/login"),
                                PATH.matcher("/css/**"),
                                PATH.matcher("/js/**"),
                                PATH.matcher("/icons/**"),
                                // External platform webhooks — must be public (no session/auth)
                                PATH.matcher("/api/billops/webhook/**"))
                        .permitAll()
                        // BillOps Admin Dashboard - ROLE_BILLOPS_ADMIN and ROLE_BILLOPS_ADMIN_SUB
                        .requestMatchers(PATH.matcher("/billops/dashboard_admin.html"))
                        .hasAnyAuthority("ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB")
                        // BillOps User Dashboard - ROLE_BILLOPS and ROLE_BILLOPS_ADMIN
                        .requestMatchers(PATH.matcher("/billops/dashboard_user.html"))
                        .hasAnyAuthority("ROLE_BILLOPS", "ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB")
                        // Unified Dashboard - ROLE_XAMOPS
                        .requestMatchers(PATH.matcher("/unified-dashboard.html"))
                        .hasAuthority("ROLE_XAMOPS")
                        // All other BillOps pages - ROLE_BILLOPS, ROLE_BILLOPS_ADMIN, ROLE_BILLOPS_ADMIN_SUB
                        .requestMatchers(PATH.matcher("/billops/**"))
                        .hasAnyAuthority("ROLE_BILLOPS", "ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PATH.matcher("/api/**")))
                .formLogin((form) -> form
                        .loginPage("/login")
                        .successHandler(authenticationSuccessHandler)
                        .failureHandler((req, res, ex) -> {
                            res.setStatus(HttpStatus.UNAUTHORIZED.value());
                            res.setContentType("application/json");
                            res.getWriter().write("{\"message\": \"Invalid username or password.\"}");
                        })
                        .permitAll())
                .logout((logout) -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());

        // Block expired-trial clients from billops APIs (admin trial-management stays exempt).
        http.addFilterAfter(trialEnforcementFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}