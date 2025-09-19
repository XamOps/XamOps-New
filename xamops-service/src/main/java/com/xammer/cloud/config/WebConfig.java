package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value; // Import Value
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Inject the allowed origins from application properties
    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;
@Override
public void addCorsMappings(CorsRegistry registry) {
    // ✅ CORRECTED: Apply CORS to all endpoints in the application, including /login
    registry.addMapping("/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
}
}