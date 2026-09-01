package com.xammer.mediaops.config;

import com.xammer.mediaops.security.ImpersonationJwtFilter;
import com.xammer.mediaops.security.MediaOpsTrialEnforcementFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final MediaOpsTrialEnforcementFilter trialEnforcementFilter;
    private final ImpersonationJwtFilter impersonationJwtFilter;

    public SecurityConfig(MediaOpsTrialEnforcementFilter trialEnforcementFilter,
                          ImpersonationJwtFilter impersonationJwtFilter) {
        this.trialEnforcementFilter = trialEnforcementFilter;
        this.impersonationJwtFilter = impersonationJwtFilter;
    }

    /**
     * BillOps "Login As" requests, which carry a billops-issued JWT instead of a
     * JSESSIONID (the frontend sends them with credentials: 'omit'). STATELESS so the
     * admin's own session is never read or replaced by an impersonated request.
     *
     * Must be ordered ahead of the session chain below, otherwise these requests fall
     * through to session-only auth and every /api/media-ops/** call returns 401.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain impersonationJwtFilterChain(HttpSecurity http) throws Exception {
        // Security 7 removed the `and()`-chained configurers and the authorizeRequests /
        // antMatchers DSL; the lambda form below is the only remaining API. The rules are
        // unchanged: same paths, same permitAll/authenticated split, same session policy.
        http
            .securityMatcher(request -> request.getHeader("Authorization") != null)
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/media-ops-ws/**", "/browser-stream/**", "/media/**").permitAll()
                .anyRequest().authenticated())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.addFilterBefore(impersonationJwtFilter, UsernamePasswordAuthenticationFilter.class);
        // Impersonated clients are subject to the same trial gate as session users.
        http.addFilterAfter(trialEnforcementFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // Return 401 JSON instead of redirecting to /login (this service has no login page)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/media-ops-ws/**", "/browser-stream/**", "/media/**").permitAll()
                .requestMatchers("/api/media-ops/**").authenticated()
                .anyRequest().authenticated())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        // Block expired-trial clients from media-ops APIs (runs after auth context is restored).
        http.addFilterAfter(trialEnforcementFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Spring Boot auto-registers every Filter bean with the servlet container on top of
     * the security chain. Left enabled, the impersonation filter would also run outside
     * Spring Security, where OncePerRequestFilter's "already filtered" marker makes the
     * in-chain instance a no-op and the request ends up unauthenticated.
     */
    @Bean
    public FilterRegistrationBean<ImpersonationJwtFilter> disableImpersonationJwtFilterAutoRegistration(
            ImpersonationJwtFilter filter) {
        FilterRegistrationBean<ImpersonationJwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Same rationale as above — the trial gate must run inside the security chain, where
     * the authenticated principal is available, not as a standalone container filter.
     */
    @Bean
    public FilterRegistrationBean<MediaOpsTrialEnforcementFilter> disableTrialFilterAutoRegistration(
            MediaOpsTrialEnforcementFilter filter) {
        FilterRegistrationBean<MediaOpsTrialEnforcementFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
