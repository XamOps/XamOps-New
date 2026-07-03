package com.xammer.mediaops.config;

import com.xammer.mediaops.security.MediaOpsTrialEnforcementFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final MediaOpsTrialEnforcementFilter trialEnforcementFilter;

    public SecurityConfig(MediaOpsTrialEnforcementFilter trialEnforcementFilter) {
        this.trialEnforcementFilter = trialEnforcementFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            // Return 401 JSON instead of redirecting to /login (this service has no login page)
            .exceptionHandling()
                .authenticationEntryPoint((req, res, ex) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((req, res, ex) ->
                    res.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
            .and()
            .authorizeRequests()
                .antMatchers("/actuator/health", "/media-ops-ws/**", "/browser-stream/**", "/media/**").permitAll()
                .antMatchers("/api/media-ops/**").authenticated()
                .anyRequest().authenticated()
            .and()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);

        // Block expired-trial clients from media-ops APIs (runs after auth context is restored).
        http.addFilterAfter(trialEnforcementFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
