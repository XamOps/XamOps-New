package com.xammer.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture detection gates the EC2 apply path, so a false "same architecture"
 * result would let a resize produce an instance that cannot boot.
 */
class Ec2InstanceTypesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "m6g.xlarge", "c7g.large", "t4g.micro", "r8g.medium",
            "m7gd.large",   // suffix letters after the 'g'
            "c6gn.xlarge",  // network-optimised Graviton
            "a1.medium",    // first-gen Graviton, no generation digit pattern
            "M6G.XLARGE"    // case-insensitive
    })
    @DisplayName("classifies Graviton families as arm64")
    void detectsArm64(String type) {
        assertEquals(Ec2InstanceTypes.ARCH_ARM64, Ec2InstanceTypes.architectureOf(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "m5.large", "t3.medium", "c5.xlarge", "r5.2xlarge", "m6i.large",
            "m6a.large",     // AMD, still x86
            "c5n.xlarge",    // network-optimised Intel
            "g4dn.xlarge",   // 'g' is the family prefix, not a Graviton suffix
            "g5.xlarge",
            "inf1.xlarge",
            "trn1.2xlarge",
            "m5dn.large"
    })
    @DisplayName("classifies Intel and AMD families as x86_64, including g-prefixed GPU types")
    void detectsX86(String type) {
        assertEquals(Ec2InstanceTypes.ARCH_X86_64, Ec2InstanceTypes.architectureOf(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "m5", "..."})
    @DisplayName("returns unknown for unparseable names")
    void detectsUnknown(String type) {
        assertEquals(Ec2InstanceTypes.ARCH_UNKNOWN, Ec2InstanceTypes.architectureOf(type));
    }

    @Test
    @DisplayName("returns unknown for null")
    void detectsUnknownForNull() {
        assertEquals(Ec2InstanceTypes.ARCH_UNKNOWN, Ec2InstanceTypes.architectureOf(null));
    }

    @Test
    @DisplayName("flags x86 to Graviton as an architecture change")
    void flagsCrossArchitecture() {
        assertTrue(Ec2InstanceTypes.isArchitectureChange("m5.4xlarge", "m6g.xlarge"));
        assertTrue(Ec2InstanceTypes.isArchitectureChange("t3.large", "t4g.large"));
        assertTrue(Ec2InstanceTypes.isArchitectureChange("m6g.xlarge", "m5.xlarge"));
    }

    @Test
    @DisplayName("allows same-architecture downsizes")
    void allowsSameArchitecture() {
        assertFalse(Ec2InstanceTypes.isArchitectureChange("m5.4xlarge", "m5.xlarge"));
        assertFalse(Ec2InstanceTypes.isArchitectureChange("m5.4xlarge", "m6a.xlarge"));
        assertFalse(Ec2InstanceTypes.isArchitectureChange("m6g.2xlarge", "c7g.large"));
    }

    @Test
    @DisplayName("does not block a resize when either type is unparseable")
    void doesNotBlockOnUnknown() {
        assertFalse(Ec2InstanceTypes.isArchitectureChange("garbage", "m5.large"));
        assertFalse(Ec2InstanceTypes.isArchitectureChange("m5.large", "garbage"));
    }
}
