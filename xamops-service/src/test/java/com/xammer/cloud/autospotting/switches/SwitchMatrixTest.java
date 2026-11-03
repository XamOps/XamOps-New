package com.xammer.cloud.autospotting.switches;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate.ActionKind;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate.Code;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate.Decision;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AC-SPN-04 kill-switch matrix test (design-core.md §6.3, design-ux-ops.md §9.1,
 * plan-phase-05.md task 5.1). An {@link ApplicationContextRunner} builds {@link ActionGate} with the
 * real {@link AutoSpottingSwitchProperties} and {@link AutoSpottingFeatureFlags} beans from supplied
 * properties, and every case asserts the exact §6.3 cell: allow, or the exact error code.
 *
 * <p>Columns covered for the confirm actions: a global off, b cloud off, c conversions off, c' not
 * allowlisted, d account off, e freeze, p provider not ready, and the switch-store-unreadable column
 * (SwitchState with {@code switchStoreUnreadable=true}). The PAUSED-fallback rows of §6.4 are the
 * reconciler's behavior (Phase 08); here the matrix test pins the gate's verdict for every confirm
 * action, which is the AC-SPN-04 contract for this phase.
 */
class SwitchMatrixTest {

    // Base properties: the deployed "all on except provider-ready and allowlist-only" shape so a
    // single engaged switch is the only thing blocking, isolating each column. conversions-enabled
    // is turned on here (Phase A default is false) so the (c) column is exercised by its own case.
    private static final String[] ALL_OPEN = {
            "autospotting.enabled=true",
            "autospotting.aws.enabled=true",
            "autospotting.gcp.enabled=true",
            "autospotting.aws.conversions-enabled=true",
            "autospotting.gcp.conversions-enabled=true",
            "autospotting.aws.conversions-allowlist-only=false",
            "autospotting.gcp.conversions-allowlist-only=false",
            "autospotting.aws.v2-mutations-enabled=true",
            "autospotting.gcp.v2-mutations-enabled=true",
    };

    private static final long ACCOUNT_ID = 123L;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GateConfig.class);

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(ACCOUNT_ID);
        return a;
    }

    private static SwitchState empty() {
        return new SwitchState(Set.of(), Set.of(ACCOUNT_ID), false, false);
    }

    private static SwitchState engaged(String... scopeKeys) {
        return new SwitchState(Set.of(scopeKeys), Set.of(ACCOUNT_ID), false, false);
    }

    private static SwitchState storeUnreadable() {
        return new SwitchState(Set.of(), Set.of(), true, false);
    }

    /** A gate built from ALL_OPEN plus any extra property overrides, run against one case. */
    private void withGate(String[] extraProps, java.util.function.Consumer<ActionGate> assertion) {
        String[] props = Stream.concat(Stream.of(ALL_OPEN), Stream.of(extraProps)).toArray(String[]::new);
        runner.withPropertyValues(props).run(ctx -> assertion.accept(ctx.getBean(ActionGate.class)));
    }

    private void withGate(java.util.function.Consumer<ActionGate> assertion) {
        withGate(new String[0], assertion);
    }

    // ── allow: every confirm action under no engaged switch and the open config ─────────────

    @ParameterizedTest
    @MethodSource("allGatedKinds")
    void everyGatedActionIsAllowedWhenNothingBlocks(ActionKind kind) {
        withGate(gate -> assertThat(gate.check(kind, account(), Provider.AWS, empty()).isAllowed())
                .as("%s allowed when open", kind)
                .isTrue());
    }

    static Stream<ActionKind> allGatedKinds() {
        return Stream.of(ActionKind.ENABLE, ActionKind.ADOPT, ActionKind.CONFIG_APPLY,
                ActionKind.PROFILE_CHANGE, ActionKind.ACCEPT_BASELINE, ActionKind.MIG_POLICY_UPDATE,
                ActionKind.REVERT);
    }

    // ── column a..e, p, store-unreadable for ENABLE/ADOPT/CONFIG (full column) ───────────────

    @ParameterizedTest
    @MethodSource("fullColumnCases")
    void fullColumnActionsReturnTheExactCode(ActionKind kind, SwitchState state, String[] props, Code expected) {
        withGate(props, gate -> {
            Decision d = gate.check(kind, account(), Provider.AWS, state);
            assertThat(d.isAllowed()).as("%s blocked for %s", kind, expected).isFalse();
            assertThat(d.code()).isEqualTo(expected);
        });
    }

    static Stream<Arguments> fullColumnCases() {
        String[] none = new String[0];
        return Stream.of(ActionKind.ENABLE, ActionKind.ADOPT, ActionKind.CONFIG_APPLY,
                        ActionKind.PROFILE_CHANGE, ActionKind.ACCEPT_BASELINE)
                .flatMap(kind -> Stream.of(
                        // a global off: db GLOBAL, and property off.
                        Arguments.of(kind, engaged("GLOBAL"), none, Code.AS_DISABLED),
                        Arguments.of(kind, empty(), new String[]{"autospotting.enabled=false"}, Code.AS_DISABLED),
                        // b cloud off: db CLOUD:aws, and property off.
                        Arguments.of(kind, engaged("CLOUD:aws"), none, Code.AS_CLOUD_DISABLED),
                        Arguments.of(kind, empty(), new String[]{"autospotting.aws.enabled=false"}, Code.AS_CLOUD_DISABLED),
                        // c conversions off, property half: property off, no DB override.
                        Arguments.of(kind, empty(),
                                new String[]{"autospotting.aws.conversions-enabled=false"}, Code.AS_CONVERSIONS_PAUSED),
                        // c conversions off, DB half (tighten): db CONVERSIONS:aws engaged while the
                        // property stays on (ALL_OPEN keeps conversions-enabled=true), so the block
                        // comes from the DB override alone — property AND NOT db.engaged.
                        Arguments.of(kind, engaged("CONVERSIONS:aws"), none, Code.AS_CONVERSIONS_PAUSED),
                        // d account off.
                        Arguments.of(kind, engaged("ACCOUNT:123"), none, Code.AS_ACCOUNT_DISABLED),
                        // e freeze.
                        Arguments.of(kind, engaged("FREEZE"), none, Code.AS_FROZEN),
                        // p provider not ready (property false).
                        Arguments.of(kind, empty(), new String[]{"autospotting.aws.v2-mutations-enabled=false"},
                                Code.AS_PROVIDER_NOT_READY),
                        // switch store unreadable.
                        Arguments.of(kind, storeUnreadable(), none, Code.AS_SWITCH_UNREADABLE)));
    }

    // ── c' allowlist: only ENABLE/ADOPT, and only while allowlist-only is on ────────────────

    @ParameterizedTest
    @MethodSource("conversionKinds")
    void enableBlockedWhenNotAllowlistedUnderAllowlistOnly(ActionKind kind) {
        SwitchState notAllowlisted = new SwitchState(Set.of(), Set.of(), false, false);
        withGate(new String[]{"autospotting.aws.conversions-allowlist-only=true"}, gate -> {
            Decision d = gate.check(kind, account(), Provider.AWS, notAllowlisted);
            assertThat(d.isAllowed()).isFalse();
            assertThat(d.code()).isEqualTo(Code.AS_NOT_ALLOWLISTED);
        });
    }

    @ParameterizedTest
    @MethodSource("conversionKinds")
    void enableAllowedWhenAllowlistedUnderAllowlistOnly(ActionKind kind) {
        withGate(new String[]{"autospotting.aws.conversions-allowlist-only=true"},
                gate -> assertThat(gate.check(kind, account(), Provider.AWS, empty()).isAllowed()).isTrue());
    }

    static Stream<ActionKind> conversionKinds() {
        return Stream.of(ActionKind.ENABLE, ActionKind.ADOPT);
    }

    @Test
    void allowlistDoesNotGateConfigApply() {
        // CONFIG_APPLY is gated by (a),(b),(c),(d),(e),(p) but not (c'): an un-allowlisted account
        // can still have its managed config applied once conversions are on.
        SwitchState notAllowlisted = new SwitchState(Set.of(), Set.of(), false, false);
        withGate(new String[]{"autospotting.aws.conversions-allowlist-only=true"},
                gate -> assertThat(gate.check(ActionKind.CONFIG_APPLY, account(), Provider.AWS, notAllowlisted)
                        .isAllowed()).isTrue());
    }

    // ── MIG_POLICY_UPDATE: ignores c, c', p; still gated by a,b,d,e, store ──────────────────

    @Test
    void migPolicyUpdateAllowedWhenConversionsOffAndNotAllowlistedAndProviderNotReady() {
        SwitchState notAllowlisted = new SwitchState(Set.of(), Set.of(), false, false);
        withGate(new String[]{
                "autospotting.gcp.conversions-enabled=false",
                "autospotting.gcp.conversions-allowlist-only=true",
                "autospotting.gcp.v2-mutations-enabled=false"},
                gate -> assertThat(gate.check(ActionKind.MIG_POLICY_UPDATE, account(), Provider.GCP, notAllowlisted)
                        .isAllowed()).isTrue());
    }

    @ParameterizedTest
    @MethodSource("migBlockingCases")
    void migPolicyUpdateStillBlockedByAbDeAndStore(SwitchState state, String[] props, Code expected) {
        withGate(props, gate -> {
            Decision d = gate.check(ActionKind.MIG_POLICY_UPDATE, account(), Provider.GCP, state);
            assertThat(d.isAllowed()).isFalse();
            assertThat(d.code()).isEqualTo(expected);
        });
    }

    static Stream<Arguments> migBlockingCases() {
        String[] none = new String[0];
        return Stream.of(
                Arguments.of(engaged("GLOBAL"), none, Code.AS_DISABLED),
                Arguments.of(engaged("CLOUD:gcp"), none, Code.AS_CLOUD_DISABLED),
                Arguments.of(engaged("ACCOUNT:123"), none, Code.AS_ACCOUNT_DISABLED),
                Arguments.of(engaged("FREEZE"), none, Code.AS_FROZEN),
                Arguments.of(storeUnreadable(), none, Code.AS_SWITCH_UNREADABLE));
    }

    // ── REVERT: ignores a-d and c'; only e and p (and store) block ──────────────────────────

    @Test
    void revertAllowedUnderGlobalCloudConversionsAccountOff() {
        SwitchState many = new SwitchState(
                Set.of("GLOBAL", "CLOUD:aws", "CONVERSIONS:aws", "ACCOUNT:123"), Set.of(), false, false);
        withGate(new String[]{
                "autospotting.enabled=false",
                "autospotting.aws.enabled=false",
                "autospotting.aws.conversions-enabled=false",
                "autospotting.aws.conversions-allowlist-only=true"},
                gate -> assertThat(gate.check(ActionKind.REVERT, account(), Provider.AWS, many).isAllowed())
                        .isTrue());
    }

    @Test
    void revertBlockedByFreeze() {
        withGate(gate -> {
            Decision d = gate.check(ActionKind.REVERT, account(), Provider.AWS, engaged("FREEZE"));
            assertThat(d.code()).isEqualTo(Code.AS_FROZEN);
        });
    }

    @Test
    void revertBlockedByProviderNotReady() {
        withGate(new String[]{"autospotting.aws.v2-mutations-enabled=false"}, gate -> {
            Decision d = gate.check(ActionKind.REVERT, account(), Provider.AWS, empty());
            assertThat(d.code()).isEqualTo(Code.AS_PROVIDER_NOT_READY);
        });
    }

    @Test
    void revertBlockedByStoreUnreadable() {
        withGate(gate -> {
            Decision d = gate.check(ActionKind.REVERT, account(), Provider.AWS, storeUnreadable());
            assertThat(d.code()).isEqualTo(Code.AS_SWITCH_UNREADABLE);
        });
    }

    // ── the §6.3 statuses (envelope) ────────────────────────────────────────────────────────

    @Test
    void statusesMatchTheMatrix() {
        assertThat(Code.AS_DISABLED.status().value()).isEqualTo(503);
        assertThat(Code.AS_CLOUD_DISABLED.status().value()).isEqualTo(503);
        assertThat(Code.AS_CONVERSIONS_PAUSED.status().value()).isEqualTo(409);
        assertThat(Code.AS_NOT_ALLOWLISTED.status().value()).isEqualTo(409);
        assertThat(Code.AS_ACCOUNT_DISABLED.status().value()).isEqualTo(409);
        assertThat(Code.AS_FROZEN.status().value()).isEqualTo(503);
        assertThat(Code.AS_PROVIDER_NOT_READY.status().value()).isEqualTo(409);
        assertThat(Code.AS_SWITCH_UNREADABLE.status().value()).isEqualTo(503);
    }

    /** Minimal wiring: the gate and its two property beans, built from the runner's properties. */
    static class GateConfig {

        @org.springframework.context.annotation.Bean
        AutoSpottingSwitchProperties switchProperties(
                @org.springframework.beans.factory.annotation.Value("${autospotting.aws.enabled:true}") boolean a,
                @org.springframework.beans.factory.annotation.Value("${autospotting.gcp.enabled:true}") boolean b,
                @org.springframework.beans.factory.annotation.Value("${autospotting.aws.conversions-allowlist-only:true}") boolean c,
                @org.springframework.beans.factory.annotation.Value("${autospotting.gcp.conversions-allowlist-only:true}") boolean d,
                @org.springframework.beans.factory.annotation.Value("${autospotting.aws.v2-mutations-enabled:false}") boolean e,
                @org.springframework.beans.factory.annotation.Value("${autospotting.gcp.v2-mutations-enabled:false}") boolean f,
                @org.springframework.beans.factory.annotation.Value("${autospotting.ledger.aws.writer-enabled:false}") boolean g,
                @org.springframework.beans.factory.annotation.Value("${autospotting.ledger.gcp.writer-enabled:false}") boolean h,
                @org.springframework.beans.factory.annotation.Value("${autospotting.ledger.billing-enabled:false}") boolean i,
                @org.springframework.beans.factory.annotation.Value("${autospotting.mutations.dry-run:false}") boolean j) {
            return new AutoSpottingSwitchProperties(a, b, c, d, e, f, g, h, i, j);
        }

        @org.springframework.context.annotation.Bean
        AutoSpottingFeatureFlags featureFlags(
                @org.springframework.beans.factory.annotation.Value("${autospotting.aws.conversions-enabled:false}") boolean aws,
                @org.springframework.beans.factory.annotation.Value("${autospotting.gcp.conversions-enabled:false}") boolean gcp) {
            return new AutoSpottingFeatureFlags(aws, gcp, false, false);
        }

        @org.springframework.context.annotation.Bean
        ActionGate actionGate(
                AutoSpottingSwitchProperties props,
                AutoSpottingFeatureFlags flags,
                @org.springframework.beans.factory.annotation.Value("${autospotting.enabled:false}") boolean globalEnabled) {
            return new ActionGate(props, flags, globalEnabled);
        }
    }
}
