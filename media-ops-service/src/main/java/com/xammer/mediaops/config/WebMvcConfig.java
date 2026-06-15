package com.xammer.mediaops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${mediaops.storage.base-dir:/tmp/xamops-media}")
    private String storageBaseDir;

    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOriginsRaw;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve /media/** from the configured media storage directory.
        // e.g. GET /media/jobs/3/master.m3u8 → /Users/admin/xamops-media/jobs/3/master.m3u8
        registry.addResourceHandler("/media/**")
                .addResourceLocations("file:" + storageBaseDir + "/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/media/**")
                .allowedOrigins(allowedOriginsRaw.split(","))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
