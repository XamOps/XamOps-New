package com.xammer.cloud.service.automation.workload.execute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HPA executor must tell Spring which constructor to use, or the whole context fails to start.
 *
 * <p>Same trap {@code ActionCommandContractTest.theExecutorTellsSpringWhichConstructorToUse} guards
 * for the vertical executor, and the same reason it is asserted by reflection rather than by booting:
 * {@link com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties} asks the
 * container for an {@link HpaApplyExecutor} bean <em>while it is being built</em>. If this class had
 * two constructors and neither were annotated, Spring would fall back to a no-arg constructor that
 * does not exist and the entire application context would fail — in every profile, offline, before
 * any port is bound. A unit test that calls the constructor directly cannot see that.
 */
class HpaExecutorWiringTest {

    @Test
    @DisplayName("HpaApplyExecutor has exactly one @Autowired constructor, so Spring can wire it")
    void theExecutorTellsSpringWhichConstructorToUse() {
        Constructor<?>[] constructors = HpaApplyExecutor.class.getDeclaredConstructors();

        assertThat(constructors)
                .as("HpaApplyExecutor has %d constructors; exactly one must carry @Autowired or the"
                        + " container cannot build it and the whole context fails to start",
                        constructors.length)
                .filteredOn(c -> c.isAnnotationPresent(Autowired.class))
                .hasSize(1);
    }
}
