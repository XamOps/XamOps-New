package com.xammer.cloud.service.autospotting.obs;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AutoSpotting MDC keys and a {@link #scope(Map)} helper (design-ux-ops.md §8.1, plan-phase-10.md
 * task 10.4). Every AutoSpotting entry point — the dispatcher, the operation runner, the reconciler,
 * the job runner and the v2 controllers — opens an {@link AutoCloseable} scope at the start of its
 * unit of work and closes it in a {@code finally}, so a pooled thread never leaks one operation's
 * context into the next task.
 *
 * <p>The keys are the bounded, non-identifying context a support engineer needs to find the logs for
 * one operation: {@code operationId}, {@code groupId}, {@code provider}, {@code cloudAccountId} (the
 * {@code CloudAccount} PK, never the AWS account number or GCP project id, core §11), {@code requestId}
 * and {@code job}. The AWS/GCP request id is logged next to {@code operationId}, not carried as an MDC
 * key. These keys are what the {@code logback-spring.xml} ECS appender renders as JSON fields for the
 * AutoSpotting loggers (§8.2).
 *
 * <p>{@link AutoSpottingMdcTaskDecorator} copies {@link #OPERATION_ID}, {@link #GROUP_ID} and
 * {@link #REQUEST_ID} across executor threads; the remaining keys are set at the entry point that
 * knows them and cleared with the scope.
 */
public final class AutoSpottingMdc {

    public static final String OPERATION_ID = "operationId";
    public static final String GROUP_ID = "groupId";
    public static final String PROVIDER = "provider";
    public static final String CLOUD_ACCOUNT_ID = "cloudAccountId";
    public static final String REQUEST_ID = "requestId";
    public static final String JOB = "job";

    /** Every AutoSpotting MDC key, in render order. */
    public static final String[] KEYS = {OPERATION_ID, GROUP_ID, PROVIDER, CLOUD_ACCOUNT_ID, REQUEST_ID, JOB};

    private AutoSpottingMdc() {
    }

    /**
     * Put {@code values} into the MDC and return an {@link AutoCloseable} that restores the previous
     * values (not just clears them) on close, so a scope opened inside another scope leaves the outer
     * one intact. A null or empty value removes the key. Only the AutoSpotting keys are touched; keys
     * owned elsewhere (e.g. {@code tenant}) are left alone.
     */
    public static Scope scope(Map<String, String> values) {
        Map<String, String> previous = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            previous.put(key, MDC.get(key));
            put(key, e.getValue());
        }
        return new Scope(previous);
    }

    /** Convenience builder for a scope map; chain {@code put} then {@link Builder#open()}. */
    public static Builder builder() {
        return new Builder();
    }

    private static void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /** The {@link AutoCloseable} scope restored on close. */
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            for (Map.Entry<String, String> e : previous.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }
    }

    /** A small builder so entry points read {@code AutoSpottingMdc.builder().operationId(id).open()}. */
    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Builder operationId(Object v) {
            return set(OPERATION_ID, v);
        }

        public Builder groupId(Object v) {
            return set(GROUP_ID, v);
        }

        public Builder provider(Object v) {
            return set(PROVIDER, v);
        }

        public Builder cloudAccountId(Object v) {
            return set(CLOUD_ACCOUNT_ID, v);
        }

        public Builder requestId(Object v) {
            return set(REQUEST_ID, v);
        }

        public Builder job(Object v) {
            return set(JOB, v);
        }

        private Builder set(String key, Object v) {
            values.put(key, v == null ? null : String.valueOf(v));
            return this;
        }

        public Scope open() {
            return scope(values);
        }
    }
}
