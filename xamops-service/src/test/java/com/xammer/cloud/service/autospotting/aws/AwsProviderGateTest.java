package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The AWS mutation gate (plan-phase-18.md task 18.3, DARK rules). Both
 * {@code autospotting.aws.v2-mutations-enabled} and {@code autospotting.aws.conversions-enabled} are
 * false in every shipped profile; with either one false a confirmed ENABLE never reaches the write
 * client, on the confirm path (switch (p)) and again in the provider (defense in depth). Stubbed SDK
 * only.
 */
class AwsProviderGateTest {

    private static AutoSpottingSwitchProperties switches(boolean v2) {
        AutoSpottingSwitchProperties p = mock(AutoSpottingSwitchProperties.class);
        when(p.v2MutationsEnabled(true)).thenReturn(v2);
        return p;
    }

    private static AutoSpottingFeatureFlags flags(boolean conversions) {
        return new AutoSpottingFeatureFlags(conversions, false, false, false);
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,true"})
    void theGateOpensOnlyWhenBothSwitchesAreOn(boolean v2, boolean conversions, boolean expectedOpen) {
        AwsProviderGate gate = new AwsProviderGate(switches(v2), flags(conversions));
        assertThat(gate.open()).isEqualTo(expectedOpen);
        if (expectedOpen) {
            gate.requireOpen();
        } else {
            assertThatThrownBy(gate::requireOpen).isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY");
                assertThat(e.getStatus().value()).isEqualTo(409);
            });
        }
    }

    @Test
    void withTheFlagFalseAConfirmedEnableNeverReachesTheMutatingClient() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), false);
        String target = f.plan("conservative").targetJson();
        OperationContext op = f.context(OperationKind.ENABLE, target, null);

        assertThatThrownBy(() -> f.provider.executeConversion(op)).isInstanceOfSatisfying(
                AutoSpottingApiException.class, e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThatThrownBy(() -> f.provider.restore(op, f.plan("conservative").snapshotToStore()))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThatThrownBy(() -> f.services.mutating(f.account, FakeAws.REGION))
                .isInstanceOf(AutoSpottingApiException.class);

        assertThat(f.writeCalls()).isEmpty();
        verifyNoInteractions(f.write);
        f.aws.assertReadOnly();
    }

    @Test
    void withBothSwitchesOnTheProviderReturnsTheProgram() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
        String target = f.plan("conservative").targetJson();
        assertThat(f.provider.executeConversion(f.context(OperationKind.ENABLE, target, null)).size()).isEqualTo(7);
    }

    @Test
    void theConfirmPathRefusesAnAwsEnableWhileTheProviderSwitchIsOff() {
        // conversions on, provider programs off: switch (p) is the one that blocks.
        AutoSpottingSwitchProperties p = new AutoSpottingSwitchProperties(true, true, false, false, false, false,
                false, false, false, false);
        ActionGate gate = new ActionGate(p, flags(true), true);
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        SwitchState open = new SwitchState(Set.of(), Set.of(7L), false, false);
        for (ActionGate.ActionKind kind : List.of(ActionGate.ActionKind.ENABLE, ActionGate.ActionKind.ADOPT,
                ActionGate.ActionKind.CONFIG_APPLY, ActionGate.ActionKind.PROFILE_CHANGE)) {
            ActionGate.Decision d = gate.check(kind, account, Provider.AWS, open);
            assertThat(d.isAllowed()).as("%s", kind).isFalse();
            assertThat(d.code()).isEqualTo(ActionGate.Code.AS_PROVIDER_NOT_READY);
        }
    }

    @Test
    void noShippedProfileTurnsOnAnAwsMutationSwitch() throws IOException {
        List<String> checked = new java.util.ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:application*.properties")) {
            Properties props = new Properties();
            try (InputStream in = r.getInputStream()) {
                props.load(in);
            }
            checked.add(String.valueOf(r.getFilename()));
            assertThat(props.getProperty("autospotting.aws.v2-mutations-enabled", "false").trim())
                    .as("v2-mutations-enabled in %s", r.getFilename()).isEqualToIgnoringCase("false");
            assertThat(props.getProperty("autospotting.aws.conversions-enabled", "false").trim())
                    .as("conversions-enabled in %s", r.getFilename()).isEqualToIgnoringCase("false");
        }
        assertThat(checked).contains("application.properties");
    }
}
