package com.xammer.cloud.service.autospotting;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test support: the {@link AutoSpottingFeatureFlags} Spring builds when no {@code autospotting.*}
 * property is set anywhere, so every value comes from the code default in its {@code @Value}.
 * This is the "default config (no property line)" the containment tests run under (AC-LED-19).
 */
public final class DefaultAutoSpottingFlags {

    private DefaultAutoSpottingFlags() {
    }

    public static AutoSpottingFeatureFlags load() {
        AtomicReference<AutoSpottingFeatureFlags> flags = new AtomicReference<>();
        new ApplicationContextRunner()
                .withBean(AutoSpottingFeatureFlags.class)
                .run(context -> flags.set(context.getBean(AutoSpottingFeatureFlags.class)));
        if (flags.get() == null) {
            throw new IllegalStateException("AutoSpottingFeatureFlags bean was not created");
        }
        return flags.get();
    }
}
