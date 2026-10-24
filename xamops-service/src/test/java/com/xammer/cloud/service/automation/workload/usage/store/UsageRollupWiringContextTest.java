package com.xammer.cloud.service.automation.workload.usage.store;

import com.xammer.cloud.config.SchedulingConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cross-FEAT wiring guard: the usage-rollup beans resolve through Spring the way they will in the
 * service, not only through the constructors the unit tests call.
 *
 * <p>Covers the seams compilation does not: {@link AgentIngestService} has two constructors (Spring
 * must pick the {@code @Autowired} one that takes {@link UsageSampleCapture}); the capture's
 * {@code @Qualifier} executor must win over other {@link TaskExecutor} beans (the scheduler is one);
 * the store's unqualified {@link DataSource} must be the {@code @Primary} one, not
 * {@code masterDataSource}; and both {@code @Scheduled} cron placeholders must resolve to their
 * defaults when the keys are absent. The full {@code @SpringBootTest} is {@code @Disabled} (needs
 * PostgreSQL/Redis/Kafka), so this slice is what catches a broken wiring before a UAT deploy.
 */
class UsageRollupWiringContextTest {

    private static final DataSource PRIMARY = mock(DataSource.class);
    private static final DataSource MASTER = mock(DataSource.class);

    @Configuration
    static class Infrastructure {
        @Bean
        @Primary
        DataSource primaryDataSource() {
            return PRIMARY;
        }

        @Bean(name = "masterDataSource")
        DataSource masterDataSource() {
            return MASTER;
        }

        /** A decoy executor: an unqualified TaskExecutor lookup would now be ambiguous. */
        @Bean(name = "backgroundTaskExecutor")
        TaskExecutor backgroundTaskExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        KubernetesClusterConfigRepository kubernetesClusterConfigRepository() {
            return mock(KubernetesClusterConfigRepository.class);
        }

        @Bean
        AgentDataCacheRepository agentDataCacheRepository() {
            return mock(AgentDataCacheRepository.class);
        }

        @Bean
        AgentEventRepository agentEventRepository() {
            return mock(AgentEventRepository.class);
        }

        @Bean
        CloudAccountRepository cloudAccountRepository() {
            return mock(CloudAccountRepository.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Infrastructure.class, SchedulingConfig.class,
                    UsageRollupProperties.class, UsageRollupStore.class, UsageRollupConfig.class,
                    UsageSampleCapture.class, UsageRollupJob.class, AgentIngestService.class);

    @Test
    @DisplayName("ingest gets the capture, the capture gets the rollup executor, the store gets the primary DataSource")
    void beansResolveAcrossFeatures() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            AgentIngestService ingest = context.getBean(AgentIngestService.class);
            UsageSampleCapture capture = context.getBean(UsageSampleCapture.class);
            assertThat(ReflectionTestUtils.getField(ingest, "usageSampleCapture")).isSameAs(capture);

            Object executor = ReflectionTestUtils.getField(capture, "executor");
            assertThat(executor).isSameAs(context.getBean(UsageRollupConfig.EXECUTOR_BEAN));
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(1);
            assertThat(pool.getMaxPoolSize()).isEqualTo(1);
            assertThat(pool.getQueueCapacity()).isEqualTo(50);

            JdbcTemplate jdbc = (JdbcTemplate) ReflectionTestUtils.getField(
                    context.getBean(UsageRollupStore.class), "jdbc");
            assertThat(Objects.requireNonNull(jdbc).getDataSource()).isSameAs(PRIMARY);

            assertThat(context.getBean(UsageRollupProperties.class).enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("both @Scheduled jobs register with their default crons when no key is set")
    void scheduledCronsResolveWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(cronsOf(context.getBean(ScheduledAnnotationBeanPostProcessor.class)))
                    .contains(UsageRollupProperties.DEFAULT_HOURLY_CRON,
                            UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
        });
    }

    @Test
    @DisplayName("cron keys, when set, override the defaults")
    void scheduledCronsHonourKeys() {
        runner.withPropertyValues(
                UsageRollupProperties.HOURLY_CRON_KEY + "=0 11 * * * *",
                UsageRollupProperties.MAINTENANCE_CRON_KEY + "=0 45 2 * * *"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            List<String> crons = cronsOf(context.getBean(ScheduledAnnotationBeanPostProcessor.class));
            assertThat(crons).contains("0 11 * * * *", "0 45 2 * * *")
                    .doesNotContain(UsageRollupProperties.DEFAULT_HOURLY_CRON,
                            UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
        });
    }

    private static List<String> cronsOf(ScheduledAnnotationBeanPostProcessor processor) {
        return processor.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(t -> ((CronTask) t).getExpression())
                .toList();
    }
}
