package com.xammer.billops.config;

import com.xammer.cloud.domain.type.LobTextWriteMode;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Logs, once at startup, how this instance writes the legacy large-object TEXT column
 * {@code cached_data.json_data} (design §3.6, NFR-6).
 *
 * <p>One INFO line with the write mode and where it came from. The mode is
 * {@link LobTextWriteMode#effective(EntityManagerFactory)}, the Hibernate setting the mapped type
 * uses; the source is whether the Spring property {@value LobTextWriteMode#PROPERTY} is set. A WARN
 * follows when the Spring property disagrees with the Hibernate setting (someone set
 * {@code spring.jpa.properties.xammer.lob-text.write-mode} directly); the Hibernate value governs.
 * Only the property name and modes are logged.
 */
@Component
public class LobTextStartupReporter {

    private static final Logger log = LoggerFactory.getLogger(LobTextStartupReporter.class);

    private final EntityManagerFactory entityManagerFactory;
    private final Environment environment;

    public LobTextStartupReporter(EntityManagerFactory entityManagerFactory, Environment environment) {
        this.entityManagerFactory = entityManagerFactory;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        LobTextWriteMode mode = LobTextWriteMode.effective(entityManagerFactory);
        boolean configured = environment.containsProperty(LobTextWriteMode.PROPERTY);
        log.info("LOB-text write mode: {} ({})", label(mode), configured ? "configured" : "default");

        String springMode = springMode();
        if (!label(mode).equals(springMode)) {
            log.warn("LOB-text write mode mismatch: {} gives {} but the Hibernate setting"
                            + " spring.jpa.properties.{} gives {}; the Hibernate setting governs",
                    LobTextWriteMode.PROPERTY, springMode, LobTextWriteMode.PROPERTY, label(mode));
        }
    }

    /** The mode the Spring property asks for, or {@code invalid}. */
    private String springMode() {
        try {
            return label(LobTextWriteMode.parse(environment.getProperty(LobTextWriteMode.PROPERTY)));
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }

    private static String label(LobTextWriteMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
