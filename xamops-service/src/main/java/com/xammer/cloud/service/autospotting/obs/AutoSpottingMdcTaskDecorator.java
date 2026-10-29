package com.xammer.cloud.service.autospotting.obs;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Copies the AutoSpotting MDC keys {@code operationId}, {@code groupId} and {@code requestId} from
 * the submitting thread onto the worker thread, so every log line from a {@code spot-*} pool thread
 * still carries them (design-core.md §4.2, fixes F-03 "scheduler threads get no MDC"). It is composed
 * with the existing {@code tenantTaskDecorator} ({@code AsyncConfig.java:35}) on every AutoSpotting
 * executor, so tenant, cost-source and the full MDC map ride along as before, and these three keys
 * are set explicitly even when the submitting thread's MDC map is otherwise empty.
 *
 * <p>Only these three keys are restored and cleared, so the decorator never touches a key owned by
 * {@code tenantTaskDecorator} (it sets the whole MDC map, then this adds the three). On the way out,
 * each key is restored to the value it had on the worker thread before the task ran, so chained
 * decorators and pooled-thread reuse stay clean.
 */
@Component
public class AutoSpottingMdcTaskDecorator implements TaskDecorator {

    public static final String OPERATION_ID = "operationId";
    public static final String GROUP_ID = "groupId";
    public static final String REQUEST_ID = "requestId";

    private static final String[] KEYS = {OPERATION_ID, GROUP_ID, REQUEST_ID};

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = captureFromSubmitter();
        return () -> {
            Map<String, String> previous = captureFromSubmitter();
            applyOnWorker(captured);
            try {
                runnable.run();
            } finally {
                applyOnWorker(previous);
            }
        };
    }

    private static Map<String, String> captureFromSubmitter() {
        return Map.of(
                OPERATION_ID, orEmpty(MDC.get(OPERATION_ID)),
                GROUP_ID, orEmpty(MDC.get(GROUP_ID)),
                REQUEST_ID, orEmpty(MDC.get(REQUEST_ID)));
    }

    private static void applyOnWorker(Map<String, String> values) {
        for (String key : KEYS) {
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
