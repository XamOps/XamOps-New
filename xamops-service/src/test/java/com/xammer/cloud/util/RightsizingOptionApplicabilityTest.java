package com.xammer.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RightsizingOptionApplicabilityTest {

    @Nested
    @DisplayName("columns that are not resizes")
    class NonResizeColumns {

        @Test
        @DisplayName("'Not available in <region>' is blocked rather than parsed as type 'Not'")
        void notAvailableIsBlocked() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("Not available in ap-south-2", "t3.medium");

            assertThat(v.applicable()).isFalse();
            assertThat(v.targetType()).isNull();
            assertThat(v.reason()).isEqualTo("Not available in ap-south-2");
        }

        @Test
        @DisplayName("'Stay <type>' is blocked rather than parsed as type 'Stay'")
        void stayIsBlocked() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("Stay t3.large", "t3.large");

            assertThat(v.applicable()).isFalse();
            assertThat(v.targetType()).isNull();
            assertThat(v.reason()).contains("no change recommended");
        }

        @Test
        @DisplayName("a column whose price could not be resolved is blocked")
        void priceUnavailableIsBlocked() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("m6i.large (price unavailable)", "m5.xlarge");

            assertThat(v.applicable()).isFalse();
            assertThat(v.reason()).contains("Price could not be resolved");
        }

        @Test
        void nullAndBlankAreBlocked() {
            assertThat(RightsizingOptionApplicability.evaluate(null, "t3.medium").applicable()).isFalse();
            assertThat(RightsizingOptionApplicability.evaluate("   ", "t3.medium").applicable()).isFalse();
        }

        @Test
        @DisplayName("a target equal to the current type is not an actionable resize")
        void sameTypeIsBlocked() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("t3.medium ($30.37/mo)", "t3.medium");

            assertThat(v.applicable()).isFalse();
            assertThat(v.reason()).contains("matches the current type");
        }

        @Test
        void unparseableTextIsBlocked() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("review manually", "t3.medium");

            assertThat(v.applicable()).isFalse();
            assertThat(v.reason()).startsWith("Recommended type could not be parsed");
        }
    }

    @Nested
    @DisplayName("architecture crossings")
    class ArchitectureCrossings {

        @Test
        @DisplayName("Graviton to x86 is blocked and flagged as an architecture change")
        void armToX86() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("t3.nano ($4.09/mo)", "t4g.small");

            assertThat(v.applicable()).isFalse();
            assertThat(v.architectureChange()).isTrue();
            assertThat(v.targetType()).isEqualTo("t3.nano");
            assertThat(v.reason()).contains("changes CPU architecture")
                    .contains("arm64")
                    .contains("x86_64");
        }

        @Test
        @DisplayName("x86 to Graviton is blocked and flagged as an architecture change")
        void x86ToArm() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("m6g.large ($56.06/mo)", "m5.large");

            assertThat(v.applicable()).isFalse();
            assertThat(v.architectureChange()).isTrue();
        }

        @Test
        @DisplayName("g4dn is x86 despite the leading g, so m5 to g4dn is not an architecture change")
        void gpuFamilyIsNotGraviton() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("g4dn.xlarge ($383.98/mo)", "m5.xlarge");

            assertThat(v.architectureChange()).isFalse();
            assertThat(v.applicable()).isTrue();
        }
    }

    @Nested
    @DisplayName("applicable resizes")
    class ApplicableResizes {

        @Test
        @DisplayName("a same-architecture downsize is applicable, matching the t2 to t3 case in UAT")
        void sameArchitectureDownsizeIsApplicable() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("t3.small ($15.18/mo)", "t2.small");

            assertThat(v.applicable()).isTrue();
            assertThat(v.reason()).isNull();
            assertThat(v.architectureChange()).isFalse();
            assertThat(v.targetType()).isEqualTo("t3.small");
        }

        @Test
        @DisplayName("arm64 to arm64 stays applicable")
        void gravitonToGravitonIsApplicable() {
            RightsizingOptionApplicability.Verdict v =
                    RightsizingOptionApplicability.evaluate("t4g.micro ($6.13/mo)", "t4g.large");

            assertThat(v.applicable()).isTrue();
            assertThat(v.architectureChange()).isFalse();
        }

        @Test
        void bareTypeWithoutPriceIsApplicable() {
            assertThat(RightsizingOptionApplicability.evaluate("m5.large", "m5.xlarge").applicable()).isTrue();
        }
    }

    @Nested
    class TypeExtraction {

        @Test
        void extractsTypeFromVariousShapes() {
            assertThat(RightsizingOptionApplicability.extractInstanceType("t3.medium ($0.04/h)"))
                    .isEqualTo("t3.medium");
            assertThat(RightsizingOptionApplicability.extractInstanceType("t3.medium"))
                    .isEqualTo("t3.medium");
            assertThat(RightsizingOptionApplicability.extractInstanceType("  m6i.large, 2 vCPU"))
                    .isEqualTo("m6i.large");
            assertThat(RightsizingOptionApplicability.extractInstanceType("T3.Medium ($4/mo)"))
                    .isEqualTo("t3.medium");
        }

        @Test
        void returnsNullForEmptyInput() {
            assertThat(RightsizingOptionApplicability.extractInstanceType(null)).isNull();
            assertThat(RightsizingOptionApplicability.extractInstanceType("  ")).isNull();
        }
    }
}
