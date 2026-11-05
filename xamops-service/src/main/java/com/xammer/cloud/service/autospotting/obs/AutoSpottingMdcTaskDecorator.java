package com.xammer.cloud.service.autospotting.obs;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Copies the AutoSpotting MDC keys ({@link AutoSpottingMdc#KEYS}: {@code operationId}, {@code groupId},
 * {@code provider}, {@code cloudAccountId}, {@code requestId}, {@code job}) from the submitting thread
 * onto the worker thread, so every log line from a {@code spot-*} pool thread still carries them
 * (design-core.md §4.2, design-ux-ops.md §8.1, fixes F-03 "scheduler threads get no MDC"). It is
 * composed with the existing {@code tenantTaskDecorator} ({@code AsyncConfig.java:35}) on every
 * AutoSpotting executor, so tenant, cost-source and the full MDC map ride along as before, and these
 * keys are set explicitly even when the submitting thread's MDC map is otherwise empty.
 *
 * <p>Only the AutoSpotting keys are restored and cleared, so the decorator never touches a key owned
 * by {@code tenantTaskDecorator} (it sets the whole MDC map, then this adds the AutoSpotting keys). On
 * the way out, each key is restored to the value it had on the worker thread before the task ran, so
 * chained decorators and pooled-thread reuse stay clean.
 *
 * <p>The constant aliases below are kept for the audit writer and the problem advice, which already
 * reference them; they delegate to {@link AutoSpottingMdc}.
 */
@Component
public class AutoSpottingMdcTaskDecorator implements TaskDecorator {

    public static final String OPERATION_ID = AutoSpottingMdc.OPERATION_ID;
    public static final String GROUP_ID = AutoSpottingMdc.GROUP_ID;
    public static final String REQUEST_ID = AutoSpottingMdc.REQUEST_ID;

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = capture();
        return () -> {
            Map<String, String> previous = capture();
            apply(captured);
            try {
                runnable.run();
            } finally {
                apply(previous);
            }
        };
    }

    private static Map<String, String> capture() {
        Map<String, String> map = new HashMap<>();
        for (String key : AutoSpottingMdc.KEYS) {
            map.put(key, orEmpty(MDC.get(key)));
        }
        return map;
    }

    private static void apply(Map<String, String> values) {
        for (String key : AutoSpottingMdc.KEYS) {
            String value = values.get(key);
            if (value == null || value.isEmpty()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
