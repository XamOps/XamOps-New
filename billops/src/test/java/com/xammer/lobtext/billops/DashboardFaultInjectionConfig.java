package com.xammer.lobtext.billops;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The fault-injection beans of {@code AdminDashboardResiliencePgTest} (design §7.2): the
 * {@link Poisoner} and the post-processor that wraps the cache repository.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DashboardFaultInjectionConfig {

    /** Static: a post-processor must not need this configuration instance, or anything JPA, to exist. */
    @Bean
    static PoisoningPostProcessor poisoningPostProcessor(ObjectProvider<Poisoner> poisoner) {
        return new PoisoningPostProcessor(poisoner);
    }

    @Bean
    Poisoner poisoner(EntityManager entityManager) {
        return new Poisoner(entityManager);
    }
}
