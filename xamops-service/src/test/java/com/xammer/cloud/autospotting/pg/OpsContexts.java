package com.xammer.cloud.autospotting.pg;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * A public door to the Phase 07 operation-runner context for ITs outside this package (the AWS
 * conversion ITs live beside the AWS fixtures they share). Same beans, same throwaway database.
 */
public final class OpsContexts {

    private OpsContexts() {
    }

    /** The ops context over {@code db}: repositories, lease, runner, store, lifecycle. */
    public static AnnotationConfigApplicationContext contextFor(PgHarness.Database db) {
        return OpsTestSupport.contextFor(db);
    }
}
