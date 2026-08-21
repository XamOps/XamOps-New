package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudsitterPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the two CloudSitter guards that decide whether an instance gets stopped
 * automatically.
 *
 * <p>Both were previously untested and, in UAT, unexercised — the account showed 0 of 22
 * instances managed, so neither the timezone handling nor the production guard had ever run
 * against real data. These are the highest-consequence code paths in the feature: a wrong
 * answer stops a running production instance, or fails to stop an idle one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudsitterScheduleTest {

    @Mock com.xammer.cloud.repository.CloudsitterPolicyRepository policyRepo;
    @Mock com.xammer.cloud.repository.CloudsitterAssignmentRepository assignmentRepo;
    @Mock AwsClientProvider awsClientProvider;
    @Mock com.xammer.cloud.repository.CloudAccountRepository accountRepo;
    @Mock TenantService tenantService;
    @Mock AwsPricingService pricingService;
    @Mock EmailService emailService;
    @Mock CloudsitterEmailBuilder emailBuilder;

    private CloudsitterService service;

    @BeforeEach
    void setUp() {
        service = new CloudsitterService(policyRepo, assignmentRepo, awsClientProvider, accountRepo,
                new ObjectMapper(), tenantService, pricingService, emailService, emailBuilder);
    }

    /** Business hours Mon-Fri, 09:00-17:00 inclusive of 9, exclusive of 18. */
    private static CloudsitterPolicy businessHoursPolicy(String zone) {
        CloudsitterPolicy p = new CloudsitterPolicy();
        p.setId(1L);
        p.setName("Business hours");
        p.setTimeZone(zone);
        p.setScheduleJson("""
                {"MONDAY":[9,10,11,12,13,14,15,16,17],
                 "TUESDAY":[9,10,11,12,13,14,15,16,17],
                 "WEDNESDAY":[9,10,11,12,13,14,15,16,17],
                 "THURSDAY":[9,10,11,12,13,14,15,16,17],
                 "FRIDAY":[9,10,11,12,13,14,15,16,17]}
                """);
        return p;
    }

    private static Instance instanceWithTags(String... keyValuePairs) {
        Instance.Builder b = Instance.builder().instanceId("i-0123456789abcdef0");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            b.tags(Tag.builder().key(keyValuePairs[i]).value(keyValuePairs[i + 1]).build());
        }
        // tags(...) replaces rather than appends, so build the list explicitly.
        java.util.List<Tag> tags = new java.util.ArrayList<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            tags.add(Tag.builder().key(keyValuePairs[i]).value(keyValuePairs[i + 1]).build());
        }
        return Instance.builder().instanceId("i-0123456789abcdef0").tags(tags).build();
    }

    @Nested
    @DisplayName("schedule evaluation is timezone-sensitive")
    class TimezoneHandling {

        @Test
        @DisplayName("the same instant gives opposite answers in Asia/Kolkata and UTC")
        void sameInstantDiffersByZone() {
            // 06:00 UTC on Monday is 11:30 Monday in Kolkata — inside business hours there,
            // outside them in UTC. Evaluating in UTC would stop an instance at 11:30 local,
            // in the middle of the working day. This is the 5h30m shift the fix addresses.
            ZonedDateTime instant = ZonedDateTime.of(2026, 6, 8, 6, 0, 0, 0, ZoneId.of("UTC"));

            boolean inKolkata = service.checkScheduleState(
                    businessHoursPolicy("Asia/Kolkata"),
                    instant.withZoneSameInstant(ZoneId.of("Asia/Kolkata")));
            boolean inUtc = service.checkScheduleState(
                    businessHoursPolicy("UTC"), instant);

            assertThat(inKolkata).isTrue();
            assertThat(inUtc).isFalse();
        }

        @Test
        @DisplayName("an hour inside the schedule keeps the instance running")
        void activeHourRuns() {
            ZonedDateTime monday10am = ZonedDateTime.of(2026, 6, 8, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
            assertThat(service.checkScheduleState(businessHoursPolicy("Asia/Kolkata"), monday10am)).isTrue();
        }

        @Test
        @DisplayName("an hour outside the schedule stops the instance")
        void inactiveHourStops() {
            ZonedDateTime monday2am = ZonedDateTime.of(2026, 6, 8, 2, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
            assertThat(service.checkScheduleState(businessHoursPolicy("Asia/Kolkata"), monday2am)).isFalse();
        }

        @Test
        @DisplayName("a day absent from the schedule stops the instance")
        void unlistedDayStops() {
            ZonedDateTime saturdayNoon = ZonedDateTime.of(2026, 6, 13, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
            assertThat(service.checkScheduleState(businessHoursPolicy("Asia/Kolkata"), saturdayNoon)).isFalse();
        }

        @Test
        @DisplayName("the schedule boundary is inclusive at the start and exclusive after the last hour")
        void boundariesAreExact() {
            CloudsitterPolicy policy = businessHoursPolicy("Asia/Kolkata");
            ZoneId kolkata = ZoneId.of("Asia/Kolkata");

            assertThat(service.checkScheduleState(policy,
                    ZonedDateTime.of(2026, 6, 8, 9, 0, 0, 0, kolkata))).isTrue();
            assertThat(service.checkScheduleState(policy,
                    ZonedDateTime.of(2026, 6, 8, 17, 59, 0, 0, kolkata))).isTrue();
            assertThat(service.checkScheduleState(policy,
                    ZonedDateTime.of(2026, 6, 8, 18, 0, 0, 0, kolkata))).isFalse();
            assertThat(service.checkScheduleState(policy,
                    ZonedDateTime.of(2026, 6, 8, 8, 59, 0, 0, kolkata))).isFalse();
        }

        @Test
        @DisplayName("unparseable schedule JSON leaves the instance running rather than stopping it")
        void malformedScheduleFailsOpen() {
            CloudsitterPolicy broken = businessHoursPolicy("UTC");
            broken.setScheduleJson("{not json");

            // Failing open matters: a parse error must never translate into stopping a
            // customer's instance.
            assertThat(service.checkScheduleState(broken,
                    ZonedDateTime.of(2026, 6, 8, 3, 0, 0, 0, ZoneId.of("UTC")))).isTrue();
        }

        @Test
        @DisplayName("a schedule covering every hour never stops the instance")
        void alwaysOnScheduleNeverStops() {
            CloudsitterPolicy always = new CloudsitterPolicy();
            always.setId(2L);
            always.setTimeZone("UTC");
            StringBuilder hours = new StringBuilder();
            for (int h = 0; h < 24; h++) {
                hours.append(h).append(h == 23 ? "" : ",");
            }
            String all = hours.toString();
            always.setScheduleJson(("{\"MONDAY\":[%s],\"TUESDAY\":[%s],\"WEDNESDAY\":[%s],"
                    + "\"THURSDAY\":[%s],\"FRIDAY\":[%s],\"SATURDAY\":[%s],\"SUNDAY\":[%s]}")
                    .formatted(all, all, all, all, all, all, all));

            for (int h = 0; h < 24; h++) {
                assertThat(service.checkScheduleState(always,
                        ZonedDateTime.of(2026, 6, 8, h, 0, 0, 0, ZoneId.of("UTC"))))
                        .as("hour %s should be active", h)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("production tag guard")
    class ProductionGuard {

        @Test
        @DisplayName("recognises production across the environment-style keys")
        void recognisesProductionKeys() {
            assertThat(service.isProductionTagged(instanceWithTags("Environment", "production"))).isTrue();
            assertThat(service.isProductionTagged(instanceWithTags("env", "prod"))).isTrue();
            assertThat(service.isProductionTagged(instanceWithTags("stage", "prd"))).isTrue();
            assertThat(service.isProductionTagged(instanceWithTags("tier", "prod-web"))).isTrue();
            assertThat(service.isProductionTagged(instanceWithTags("stack", "Live"))).isTrue();
        }

        @Test
        @DisplayName("matching is case-insensitive on both key and value")
        void caseInsensitive() {
            assertThat(service.isProductionTagged(instanceWithTags("ENVIRONMENT", "PRODUCTION"))).isTrue();
            assertThat(service.isProductionTagged(instanceWithTags("Env", "Prod"))).isTrue();
        }

        @Test
        @DisplayName("non-production environments are schedulable")
        void nonProductionIsSchedulable() {
            assertThat(service.isProductionTagged(instanceWithTags("Environment", "staging"))).isFalse();
            assertThat(service.isProductionTagged(instanceWithTags("env", "dev"))).isFalse();
            assertThat(service.isProductionTagged(instanceWithTags("env", "test"))).isFalse();
            assertThat(service.isProductionTagged(instanceWithTags("env", "uat"))).isFalse();
        }

        @Test
        @DisplayName("a production-looking value under an unrelated key does not match")
        void onlyEnvironmentKeysCount() {
            // "Name=prod-database" is a name, not an environment declaration. Matching it
            // would make the guard fire on most of an estate and render CloudSitter unusable.
            assertThat(service.isProductionTagged(instanceWithTags("Name", "prod-database"))).isFalse();
            assertThat(service.isProductionTagged(instanceWithTags("Owner", "live-team"))).isFalse();
        }

        @Test
        @DisplayName("an untagged instance is schedulable")
        void untaggedIsSchedulable() {
            assertThat(service.isProductionTagged(Instance.builder().instanceId("i-1").build())).isFalse();
        }

        @Test
        @DisplayName("a null tag value does not throw")
        void nullTagValueIsSafe() {
            Instance inst = Instance.builder().instanceId("i-1")
                    .tags(Tag.builder().key("env").build())
                    .build();
            assertThat(service.isProductionTagged(inst)).isFalse();
        }

        @Test
        @DisplayName("one production tag among many is enough to refuse")
        void anyProductionTagRefuses() {
            assertThat(service.isProductionTagged(instanceWithTags(
                    "Name", "web-01", "Owner", "platform", "Environment", "production"))).isTrue();
        }
    }
}
