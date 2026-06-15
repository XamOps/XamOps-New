package com.xammer.cloud.config;

import com.xammer.cloud.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link RateLimitInterceptor} for the DevOps script API
 * endpoints only, so the 100 req/min per-user limit applies to script browsing,
 * interactions, search and customization without affecting the rest of the app.
 *
 * Requirements: REQ-2.8.1 (Authentication &amp; Authorization — rate limiting)
 */
@Configuration
public class DevOpsRateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public DevOpsRateLimitConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/scripts/**", "/api/customizations/**");
    }
}
