package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.aws.AwsTypeSelector.ImageFacts;
import com.xammer.cloud.service.autospotting.aws.AwsTypeSelector.Selection;
import com.xammer.cloud.service.autospotting.aws.AwsTypeSelector.TemplateFacts;
import com.xammer.cloud.service.autospotting.aws.AwsTypeSelector.TypeFacts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** plan-phase-12.md 12.4: the §4.3 hard filter and §4.4 ranking and cap. */
class AwsTypeSelectorTest {

    private static final Set<String> AZS = Set.of("a", "b");
    private static final ImageFacts IMAGE = new ImageFacts("x86_64", "legacy-bios", true);
    private final AwsTypeSelector selector = new AwsTypeSelector(12);

    private static TypeFacts type(String name, int vcpu, long mem, double price) {
        return new TypeFacts(name, vcpu, mem, Set.of("x86_64"), Set.of("on-demand", "spot"), false, false, true,
                Set.of("legacy-bios", "uefi"), 0, null, 0, false, 3, false, true, AZS, price);
    }

    private static TypeFacts burstable(String name) {
        return new TypeFacts(name, 2, 8192, Set.of("x86_64"), Set.of("on-demand", "spot"), true, false, true,
                Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, AZS, 0.01);
    }

    private final TypeFacts original = type("m5.large", 2, 8192, 0.03);

    private Selection select(TypeFacts... candidates) {
        return selector.select(original, AZS, IMAGE, TemplateFacts.none(), List.of(candidates));
    }

    @Test
    void burstableTypesAreFiltered() {
        Selection s = select(type("m5a.large", 2, 8192, 0.025), burstable("t3.large"));
        assertThat(s.types()).contains("m5a.large").doesNotContain("t3.large");
    }

    @Test
    void theOriginalIsAlwaysFirstAndOnlyOnce() {
        Selection s = select(type("m6i.large", 2, 8192, 0.001), type("m5a.large", 2, 8192, 0.002));
        assertThat(s.types().get(0)).isEqualTo("m5.large");
        assertThat(s.types()).containsOnlyOnce("m5.large");
    }

    @Test
    void differentVcpuOrTooMuchMemoryOrTooLittleIsFiltered() {
        Selection s = select(type("m5.xlarge", 4, 16384, 0.02), type("c5.large", 2, 4096, 0.02),
                type("x1.large", 2, 24576, 0.02), type("r5.large", 2, 16384, 0.02));
        assertThat(s.types()).containsExactly("m5.large", "r5.large");
    }

    @Test
    void macAndBareMetalAndWrongArchitectureAreFiltered() {
        TypeFacts arm = new TypeFacts("m6g.large", 2, 8192, Set.of("arm64"), Set.of("spot"), false, false, true,
                Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        TypeFacts metal = new TypeFacts("m5.metal", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, true, true,
                Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        TypeFacts mac = type("mac2.metal", 2, 8192, 0.02);
        assertThat(select(arm, metal, mac).types()).containsExactly("m5.large");
    }

    @Test
    void aTypeOfferedInOnlyOneOfTwoAzsIsFiltered() {
        TypeFacts oneAz = new TypeFacts("m5a.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false, true,
                Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, Set.of("a"), 0.02);
        assertThat(select(oneAz).types()).containsExactly("m5.large");
    }

    /** D-23-05: an AL2023 AMI reports BootMode uefi-preferred; instance types list only legacy-bios/uefi. */
    @Test
    void aUefiPreferredAmiAcceptsTypesSupportingUefiOrLegacyBios() {
        ImageFacts al2023 = new ImageFacts("x86_64", "uefi-preferred", true);
        TypeFacts legacyOnly = new TypeFacts("m5a.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false,
                true, Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        Selection s = selector.select(original, AZS, al2023, TemplateFacts.none(),
                List.of(type("m6i.large", 2, 8192, 0.02), legacyOnly));
        assertThat(s.types()).containsExactlyInAnyOrder("m5.large", "m6i.large", "m5a.large");
    }

    @Test
    void anAmiWithoutABootModeAcceptsTypesSupportingUefiOrLegacyBios() {
        ImageFacts unset = new ImageFacts("x86_64", null, true);
        ImageFacts blank = new ImageFacts("x86_64", "", true);
        assertThat(selector.select(original, AZS, unset, TemplateFacts.none(),
                List.of(type("m6i.large", 2, 8192, 0.02))).types()).contains("m6i.large");
        assertThat(selector.select(original, AZS, blank, TemplateFacts.none(),
                List.of(type("m6i.large", 2, 8192, 0.02))).types()).contains("m6i.large");
    }

    @Test
    void anX86AmiWithoutABootModeRejectsAUefiOnlyTypeAndAnArmAmiAcceptsIt() {
        TypeFacts uefiOnly = new TypeFacts("m7i.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false,
                true, Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        TypeFacts armUefi = new TypeFacts("m6g.large", 2, 8192, Set.of("arm64"), Set.of("spot"), false, false,
                true, Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        TypeFacts armOriginal = new TypeFacts("m7g.large", 2, 8192, Set.of("arm64"), Set.of("on-demand", "spot"),
                false, false, true, Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.03);
        assertThat(selector.select(original, AZS, new ImageFacts("x86_64", null, true), TemplateFacts.none(),
                List.of(uefiOnly)).types()).containsExactly("m5.large");
        assertThat(selector.select(armOriginal, AZS, new ImageFacts("arm64", null, true), TemplateFacts.none(),
                List.of(armUefi)).types()).contains("m6g.large");
    }

    @Test
    void aUefiPreferredAmiAcceptsAUefiOnlyType() {
        TypeFacts uefiOnly = new TypeFacts("m7i.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false,
                true, Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        assertThat(selector.select(original, AZS, new ImageFacts("x86_64", "uefi-preferred", true),
                TemplateFacts.none(), List.of(uefiOnly)).types()).contains("m7i.large");
    }

    @Test
    void aUefiAmiStillRejectsALegacyOnlyTypeAndALegacyAmiAUefiOnlyType() {
        TypeFacts legacyOnly = new TypeFacts("m5a.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false,
                true, Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        TypeFacts uefiOnly = new TypeFacts("m7i.large", 2, 8192, Set.of("x86_64"), Set.of("spot"), false, false,
                true, Set.of("uefi"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        assertThat(selector.select(original, AZS, new ImageFacts("x86_64", "uefi", true), TemplateFacts.none(),
                List.of(legacyOnly, uefiOnly)).types()).containsExactlyInAnyOrder("m5.large", "m7i.large");
        assertThat(selector.select(original, AZS, IMAGE, TemplateFacts.none(),
                List.of(legacyOnly, uefiOnly)).types()).containsExactlyInAnyOrder("m5.large", "m5a.large");
        assertThat(AwsTypeSelector.bootsImage(Set.of(), "uefi-preferred")).isFalse();
    }

    @Test
    void aTypeWithoutSpotSupportIsFiltered() {
        TypeFacts noSpot = new TypeFacts("m5a.large", 2, 8192, Set.of("x86_64"), Set.of("on-demand"), false, false,
                true, Set.of("legacy-bios"), 0, null, 0, false, 3, false, true, AZS, 0.02);
        assertThat(select(noSpot).types()).containsExactly("m5.large");
    }

    @Test
    void sameFamilyRanksBeforeSiblingsAndThenCheaperFirst() {
        Selection s = select(type("c5.large", 2, 8192, 0.001), type("m6i.large", 2, 8192, 0.05),
                type("m7i.large", 2, 8192, 0.04));
        assertThat(s.types()).containsExactly("m5.large", "m7i.large", "m6i.large", "c5.large");
    }

    @Test
    void theCountIsCappedAtTheTargetAndNeverAboveForty() {
        List<TypeFacts> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(type("m" + (10 + i) + "i.large", 2, 8192, 0.01 + i / 1000.0));
        }
        assertThat(selector.select(original, AZS, IMAGE, TemplateFacts.none(), many).types()).hasSize(12);
        assertThat(new AwsTypeSelector(500).select(original, AZS, IMAGE, TemplateFacts.none(), many).types())
                .hasSize(AwsTypeSelector.MAX_TYPES);
    }

    @Test
    void noCompatibleTypesAndFewTypesAreReported() {
        assertThat(select().noCompatibleTypes()).isTrue();
        Selection few = select(type("m5a.large", 2, 8192, 0.02), type("m6i.large", 2, 8192, 0.02));
        assertThat(few.noCompatibleTypes()).isFalse();
        assertThat(few.fewTypes()).isTrue();
        Selection ok = select(type("m5a.large", 2, 8192, 0.02), type("m6i.large", 2, 8192, 0.02),
                type("m6a.large", 2, 8192, 0.02));
        assertThat(ok.fewTypes()).isFalse();
    }

    @Test
    void instanceStoreAndEfaAndHibernationRulesFromTheTemplate() {
        TemplateFacts needsStore = new TemplateFacts(1, false, 0, false);
        assertThat(selector.select(original, AZS, IMAGE, needsStore, List.of(type("m5a.large", 2, 8192, 0.02))).types())
                .containsExactly("m5.large");
        TemplateFacts efa = new TemplateFacts(0, true, 0, false);
        assertThat(selector.select(original, AZS, IMAGE, efa, List.of(type("m5a.large", 2, 8192, 0.02))).types())
                .containsExactly("m5.large");
        TemplateFacts hib = new TemplateFacts(0, false, 0, true);
        assertThat(selector.select(original, AZS, IMAGE, hib, List.of(type("m5a.large", 2, 8192, 0.02))).types())
                .containsExactly("m5.large");
    }
}
