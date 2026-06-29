package com.xammer.mediaops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
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

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve /media/** from the configured media storage directory.
        // e.g. GET /media/jobs/3/master.m3u8 → {storageBaseDir}/jobs/3/master.m3u8
        // Content types are applied via the servlet mime mappings bean below — the
        // resource handler resolves the type from ServletContext.getMimeType(filename).
        registry.addResourceHandler("/media/**")
                .addResourceLocations("file:" + storageBaseDir + "/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Transcoded media is public, unauthenticated and served without credentials,
        // so allow any origin. This lets the generated output URL be loaded by any
        // web-based HLS/DASH player or CDN, not just the XamOps frontend origin.
        registry.addMapping("/media/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /**
     * Registers correct content types for transcoded outputs so the generated URL
     * plays in ANY player. Without these, the servlet container reports unknown
     * extensions as application/octet-stream — which Safari and many native/web
     * players reject (especially the HLS .m3u8 manifest and .ts segments).
     */
    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> mediaMimeMappings() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("m3u8", "application/vnd.apple.mpegurl"); // HLS manifest
            mappings.add("ts", "video/mp2t");                      // HLS segment
            mappings.add("mpd", "application/dash+xml");           // DASH manifest
            mappings.add("m4s", "video/mp4");                      // fMP4/CMAF segment
            mappings.add("mp4", "video/mp4");
            mappings.add("mov", "video/quicktime");
            mappings.add("webm", "video/webm");
            mappings.add("mkv", "video/x-matroska");
            mappings.add("vtt", "text/vtt");                       // subtitles
            factory.setMimeMappings(mappings);
        };
    }
}
