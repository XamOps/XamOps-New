package com.xammer.cloud.service;

import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sts.StsClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Boot-level regression guard for the property-injected constructor.
 *
 * <p>{@link AwsClientProvider} is a {@code @Service} that Spring must instantiate through a
 * {@code @Value}-injected constructor. The focused provider tests all call the two-arg constructor
 * directly, so a class that compiles and passes those tests can still be un-instantiable by Spring
 * when it declares multiple unannotated constructors — the context simply cannot pick one. This
 * binds the provider as a real bean through an {@link ApplicationContextRunner}, the same
 * slice-of-context approach {@code K8sCostPropertiesContextTest} uses, so that failure surfaces at
 * build time rather than on the next deploy. {@link StsClient} and {@link AwsPermissionInterceptor}
 * are supplied as mock beans; nothing here reaches AWS.
 */
class AwsClientProviderContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StsClient.class, () -> mock(StsClient.class))
            .withBean(AwsPermissionInterceptor.class, () -> mock(AwsPermissionInterceptor.class))
            .withBean(AwsClientProvider.class);

    @Test
    @DisplayName("Spring instantiates the provider with the 4h (240 min) default when the property is absent")
    void bindsAsRealBeanWithDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AwsClientProvider.class);

            AwsClientProvider provider = context.getBean(AwsClientProvider.class);
            assertThat(provider.getExpireAfterAccessMinutesForTest()).isEqualTo(240L);
        });
    }

    @Test
    @DisplayName("Spring binds the configured expiry through the real @Value constructor")
    void bindsConfiguredExpiryFromProperties() {
        runner.withPropertyValues("xamops.aws.client-cache.expire-after-access-minutes=90")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    AwsClientProvider provider = context.getBean(AwsClientProvider.class);
                    assertThat(provider.getExpireAfterAccessMinutesForTest()).isEqualTo(90L);
                });
    }
}
