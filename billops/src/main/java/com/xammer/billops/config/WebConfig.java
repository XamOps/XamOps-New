package com.xammer.billops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // --- REMOVE THIS ENTIRE METHOD ---
    /*
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // This method conflicts with SecurityConfig
    }
    */
}