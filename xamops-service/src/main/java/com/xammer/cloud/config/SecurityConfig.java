package com.xammer.cloud.config;

import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import com.xammer.cloud.security.CustomLogoutSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
    private final CustomLogoutSuccessHandler logoutSuccessHandler;

    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler,
            CustomLogoutSuccessHandler logoutSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.logoutSuccessHandler = logoutSuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http

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
                        .requestMatchers(new AntPathRequestMatcher("/api/xamops/cloudguard/grafana-ingest", "POST"))
                        .permitAll()

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
                                // ✅ YOUR CODE: Preserved missing pages
                                new AntPathRequestMatcher("/cloudsitter.html"),
                                new AntPathRequestMatcher("billops/dashboard_user.html"),
                                new AntPathRequestMatcher("billops/dashboard_admin.html"),

                                // Connectivity
                                new AntPathRequestMatcher("/ws/**"),
                                new AntPathRequestMatcher("/terminal"),

                                // Public APIs
                              //  new AntPathRequestMatcher("/api/ai/**"),
                                new AntPathRequestMatcher("/api/cicd/github/runs"),
                                new AntPathRequestMatcher("/api/cicd/config/**"),
                                new AntPathRequestMatcher("/api/cicd/**"),
                                new AntPathRequestMatcher("/api/xamops/k8s/**"),
                                // ✅ YOUR CODE: Preserved missing APIs
                                new AntPathRequestMatcher("/api/xamops/eks/**"),
                                new AntPathRequestMatcher("/api/karpenter/**"),

                                new AntPathRequestMatcher("/api/autospotting/**"),
                                new AntPathRequestMatcher("/api/dev_ops_script/**"),
                                // ✅ TEAM CODE: Preserved AWS Calculator API
                                new AntPathRequestMatcher("/api/aws/**"))
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin((form) -> form
                        .loginPage("/login")
                        .successHandler(authenticationSuccessHandler)
                        .permitAll())
                .logout((logout) -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:5173",
                "https://live.xamops.com",
                "https://uat.xamops.com"));
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