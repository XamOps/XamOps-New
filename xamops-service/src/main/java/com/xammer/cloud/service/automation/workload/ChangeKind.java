package com.xammer.cloud.service.automation.workload;

/** Direction of a container's recommendation, decided against the current request, never against zero. */
public enum ChangeKind {
    /** Nothing is recommended for this container. */
    NONE,
    INCREASE,
    DECREASE,
    /** One resource up, the other down. */
    MIXED
}
