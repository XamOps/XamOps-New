package com.xammer.cloud.xampulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.xampulse.controller.XamPulseStatusController;
import com.xammer.cloud.xampulse.repository.*;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class XamPulseWiringTest {

    // ---------------- bean naming (plan 7.1 / review M4) ----------------

    @Test
    void everyXamPulseStereotypeBeanHasAnExplicitXpPrefixedNameAndNoSimpleNameIsSharedWithOtherPackages() throws Exception {
        ClassPathScanningCandidateComponentProvider p = new ClassPathScanningCandidateComponentProvider(false);
        p.setEnvironment(enabledEnvironment()); // conditional beans are only candidates when the feature is on
        p.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Map<String, List<String>> bySimpleName = new HashMap<>();
        List<String> unnamed = new ArrayList<>();
        for (BeanDefinition bd : p.findCandidateComponents("com.xammer.cloud")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            bySimpleName.computeIfAbsent(c.getSimpleName(), k -> new ArrayList<>()).add(c.getName());
            if (c.getName().startsWith("com.xammer.cloud.xampulse.") && !c.getName().contains("Test")
                    && !AnnotatedElementUtils.isAnnotated(c, ControllerAdvice.class)) {
                String name = explicitName(c);
                if (name == null || !name.startsWith("xp")) {
                    unnamed.add(c.getName() + " -> " + name);
                }
            }
        }
        assertTrue(unnamed.isEmpty(), "XamPulse beans need an explicit xp-prefixed name: " + unnamed);
        List<String> clashes = new ArrayList<>();
        bySimpleName.forEach((simple, classes) -> {
            boolean ours = classes.stream().anyMatch(n -> n.startsWith("com.xammer.cloud.xampulse."));
            if (ours && classes.size() > 1) {
                clashes.add(simple + " " + classes);
            }
        });
        assertTrue(clashes.isEmpty(), "simple-name clashes with XamPulse classes: " + clashes);
        assertTrue(Class.forName("com.xammer.cloud.xampulse.service.insight.XamPulseAnomalyDetector") != null);
        assertTrue(bySimpleName.containsKey("AnomalyDetector"), "the SRE AnomalyDetector still exists, which is why ours is prefixed");
    }

    private static String explicitName(Class<?> c) {
        for (var a : c.getAnnotations()) {
            try {
                Object v = a.annotationType().getMethod("value").invoke(a);
                if (v instanceof String s && !s.isBlank() && isStereotype(a.annotationType())) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                // annotation has no String value()
            }
        }
        return null;
    }

    private static boolean isStereotype(Class<?> annotation) {
        return annotation == Component.class || AnnotatedElementUtils.isAnnotated(annotation, Component.class);
    }

    private static org.springframework.core.env.StandardEnvironment enabledEnvironment() {
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t", Map.of("xampulse.enabled", "true")));
        return env;
    }

    // ---------------- context wiring ----------------

    @Configuration
    @ComponentScan("com.xammer.cloud.xampulse")
    static class ScanConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean CloudAccountAccessService access() { return mock(CloudAccountAccessService.class); }
        @Bean CloudAccountRepository accounts() { return mock(CloudAccountRepository.class); }
        @Bean EmailService email() { return mock(EmailService.class); }
        @Bean org.springframework.mail.javamail.JavaMailSender mailSender() { return mock(org.springframework.mail.javamail.JavaMailSender.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean SchedulingEnvironmentConfig scheduling() { return mock(SchedulingEnvironmentConfig.class); }
        @Bean XpAccountConfigRepository r1() { return mock(XpAccountConfigRepository.class); }
        @Bean XpAccountRegionRepository r2() { return mock(XpAccountRegionRepository.class); }
        @Bean XpAgentRepository r3() { return mock(XpAgentRepository.class); }
        @Bean XpAgentCredentialRepository r4() { return mock(XpAgentCredentialRepository.class); }
        @Bean XpLogSourceRepository r5() { return mock(XpLogSourceRepository.class); }
        @Bean XpAlertRuleRepository r6() { return mock(XpAlertRuleRepository.class); }
        @Bean XpAlertStateRepository r7() { return mock(XpAlertStateRepository.class); }
        @Bean XpIncidentRepository r8() { return mock(XpIncidentRepository.class); }
        @Bean XpIncidentEventRepository r9() { return mock(XpIncidentEventRepository.class); }
        @Bean XpSilenceRepository r10() { return mock(XpSilenceRepository.class); }
        @Bean XpChannelRepository r11() { return mock(XpChannelRepository.class); }
        @Bean XpSavedViewRepository r12() { return mock(XpSavedViewRepository.class); }
        @Bean XpUsageDailyRepository r13() { return mock(XpUsageDailyRepository.class); }
        @Bean XpAuditRepository r14() { return mock(XpAuditRepository.class); }
        @Bean XpMigrationLogRepository r15() { return mock(XpMigrationLogRepository.class); }
        // slice B (onboarding / agent enrolment) collaborators from the rest of the application
        @Bean com.xammer.cloud.service.AwsClientProvider awsClientProvider() { return mock(com.xammer.cloud.service.AwsClientProvider.class); }
        @Bean com.xammer.cloud.service.inventory.InventoryProjectionService inventoryProjection() { return mock(com.xammer.cloud.service.inventory.InventoryProjectionService.class); }
        @Bean com.xammer.cloud.repository.UserRepository users() { return mock(com.xammer.cloud.repository.UserRepository.class); }
        @Bean software.amazon.awssdk.services.sts.StsClient stsClient() { return mock(software.amazon.awssdk.services.sts.StsClient.class); }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ScanConfig.class)
                .withPropertyValues("cors.allowed-origins=http://localhost:3000");
    }

    @Test
    void whenDisabledNothingThatNeedsClickHouseIsCreatedButStatusStillAnswers() {
        runner().withPropertyValues("xampulse.enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(0, ctx.getBeansOfType(ClickHouseExecutor.class).size());
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.config.ClickHouseConnections.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.controller.XamPulseLogsController.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.service.alert.AlertEvaluator.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.websocket.XamPulseTailWebSocketHandler.class).length);
            var status = ctx.getBean(XamPulseStatusController.class).status();
            assertFalse(status.enabled());
            assertFalse(status.demoAvailable());
        });
    }

    @Test
    void whenEnabledTheWholeModuleWiresWithoutMissingOrCircularBeans() {
        runner().withPropertyValues("xampulse.enabled=true", "xampulse.clickhouse.url=jdbc:clickhouse://localhost:8123/xampulse",
                "xampulse.clickhouse.reader-user=r", "xampulse.clickhouse.writer-user=w").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(ClickHouseExecutor.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.controller.XamPulseLogsController.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.alert.AlertEvaluator.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.websocket.XamPulseTailWebSocketHandler.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.config.XamPulseWebSocketConfig.class));
            assertTrue(ctx.getBean(XamPulseStatusController.class).status().enabled());
            assertFalse(ctx.getBean(XamPulseStatusController.class).status().demoAvailable(), "server-side demo is not built");
        });
    }

    private static final String[] ENABLED = {"xampulse.enabled=true", "xampulse.clickhouse.url=jdbc:clickhouse://localhost:8123/xampulse",
            "xampulse.clickhouse.reader-user=r", "xampulse.clickhouse.writer-user=w"};

    private static String[] with(String... extra) {
        List<String> all = new ArrayList<>(List.of(ENABLED));
        all.addAll(List.of(extra));
        return all.toArray(new String[0]);
    }

    @Test
    void pass2BeansAreWiredWhenEnabledAndTheArchiveRunBeansAreAbsentByDefault() {
        runner().withPropertyValues(ENABLED).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.controller.XamPulseAgentFleetController.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.agent.AgentFleetService.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.agent.IngestQuotaService.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.platform.XpIdempotencyService.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.stream.TailAccessRevalidator.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.config.ClickHouseSettingsBootCheck.class));
            // the estimate endpoint is always there; the run side is flag gated and OFF by default
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.controller.XamPulseArchiveSearchController.class));
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.controller.XamPulseArchiveSearchRunController.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.service.archive.ArchiveSearchRunner.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.service.archive.ArchiveSearchJobStore.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.service.archive.ArchiveSearchExecutor.class).length);
            assertEquals(0, ctx.getBeanNamesForType(com.xammer.cloud.xampulse.service.archive.ArchiveFailureAlerter.class).length);
        });
    }

    @Test
    void archiveRunBeansExistOnlyWhenTheArchiveFlagIsOn() {
        runner().withPropertyValues(with("xampulse.archive.enabled=true")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.controller.XamPulseArchiveSearchRunController.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.archive.ArchiveSearchRunner.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.archive.ArchiveSearchJobStore.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.archive.ArchiveSearchExecutor.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.archive.ArchiveFailureAlerter.class));
            assertNotNull(ctx.getBean(com.xammer.cloud.xampulse.service.archive.ArchiveWriterJob.class));
        });
    }

    @Test
    void enabledWithoutAClickHouseUrlFailsFastWithAClearMessage() {
        runner().withPropertyValues("xampulse.enabled=true").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            assertTrue(String.valueOf(ctx.getStartupFailure().getMessage() + rootMessage(ctx.getStartupFailure()))
                    .contains("xampulse.clickhouse.url"));
        });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
