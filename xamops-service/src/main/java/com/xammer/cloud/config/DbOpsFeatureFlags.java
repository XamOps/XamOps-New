package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single owner of the DbOps feature flags (SEC-2/3, Phase 1a trust batch).
 *
 * <p>
 * Every DbOps sub-feature that can show dangerous or unverified output is disabled by default.
 * Holding the default {@code false} literal in exactly one place — here — keeps "is feature X on?"
 * a single decision rather than many {@code @Value} sites that could drift. The matching frontend
 * mirror consts in {@code frontend-app/src/constants/dbOpsFeatureFlags.ts} must be flipped in step
 * with these backend flags.
 *
 * <p>
 * Explain: the enqueue and result endpoints answer 409, the agent command poll returns 204, and a
 * late result write-back is ignored. Idle / right-sizing / health / Mongo / Redis: the controllers
 * fail closed after the SEC-1 access check and return an empty response, and the savings summary
 * zeros the disabled pillars. Every feature is fail-closed end to end.
 */
@Component
public class DbOpsFeatureFlags {

    private final boolean explainEnabled;
    private final boolean idleEnabled;
    private final boolean rightsizingEnabled;
    private final boolean healthEnabled;
    private final boolean mongoEnabled;
    private final boolean redisEnabled;

    @Autowired
    public DbOpsFeatureFlags(
            @Value("${xamops.dbops.explain.enabled:false}") boolean explainEnabled,
            @Value("${xamops.dbops.idle.enabled:false}") boolean idleEnabled,
            @Value("${xamops.dbops.rightsizing.enabled:false}") boolean rightsizingEnabled,
            @Value("${xamops.dbops.health.enabled:false}") boolean healthEnabled,
            @Value("${xamops.dbops.mongo.enabled:false}") boolean mongoEnabled,
            @Value("${xamops.dbops.redis.enabled:false}") boolean redisEnabled) {
        this.explainEnabled = explainEnabled;
        this.idleEnabled = idleEnabled;
        this.rightsizingEnabled = rightsizingEnabled;
        this.healthEnabled = healthEnabled;
        this.mongoEnabled = mongoEnabled;
        this.redisEnabled = redisEnabled;
    }

    /**
     * Backward-compatible constructor used by tests that only care about the Explain flag; the
     * five Phase-1a flags default to {@code false} (their production default), so existing
     * {@code new DbOpsFeatureFlags(false)} call sites keep compiling and keep every feature off.
     */
    public DbOpsFeatureFlags(boolean explainEnabled) {
        this(explainEnabled, false, false, false, false, false);
    }

    /** @return whether the DbOps Explain pipeline is enabled. Defaults to {@code false}. */
    public boolean isExplainEnabled() {
        return explainEnabled;
    }

    /** @return whether DbOps idle-instance detection is enabled. Defaults to {@code false}. */
    public boolean isIdleEnabled() {
        return idleEnabled;
    }

    /** @return whether DbOps right-sizing recommendations are enabled. Defaults to {@code false}. */
    public boolean isRightsizingEnabled() {
        return rightsizingEnabled;
    }

    /** @return whether DbOps health scoring / alerting is enabled. Defaults to {@code false}. */
    public boolean isHealthEnabled() {
        return healthEnabled;
    }

    /** @return whether the DbOps MongoDB agent tabs are enabled. Defaults to {@code false}. */
    public boolean isMongoEnabled() {
        return mongoEnabled;
    }

    /** @return whether the DbOps Redis agent tabs are enabled. Defaults to {@code false}. */
    public boolean isRedisEnabled() {
        return redisEnabled;
    }
}
