package com.xammer.cloud.service.gcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the machine-type shape arithmetic behind every GCP compute price.
 *
 * <p>A Compute Engine VM is billed as vCPU-hours plus GiB-hours, so an error in the shape is an
 * error in the price, applied silently to every machine of that type. Two such errors were live:
 * the shared-core E2 types were recorded as 2 vCPUs each when they are fractions of one, and N1's
 * memory ratios were applied to every family.
 */
class GcpMachineTypeShapeTest {

    private static GcpPricingService.MachineTypeSpecs parse(String machineType) {
        return GcpPricingService.parseMachineType(machineType);
    }

    @ParameterizedTest
    @CsvSource({
            // The previous table recorded 2 vCPUs for all three, overstating an e2-micro by 8x.
            "e2-micro,  0.25, 1.0",
            "e2-small,  0.5,  2.0",
            "e2-medium, 1.0,  4.0",
            "f1-micro,  0.2,  0.6",
            "g1-small,  0.5,  1.7"
    })
    @DisplayName("shared-core types have fractional vCPUs")
    void sharedCoreTypes(String machineType, double vCpus, double memoryGb) {
        var specs = parse(machineType);

        assertThat(specs.recognised()).isTrue();
        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @ParameterizedTest
    @CsvSource({
            // N1 is the only family with the 3.75 / 6.5 / 0.9 ratios.
            "n1-standard-1,  1,  3.75",
            "n1-standard-8,  8,  30.0",
            "n1-highmem-4,   4,  26.0",
            "n1-highcpu-16, 16,  14.4"
    })
    @DisplayName("N1 keeps its own memory ratios")
    void n1Ratios(String machineType, double vCpus, double memoryGb) {
        var specs = parse(machineType);

        assertThat(specs.catalogFamily()).isEqualTo("N1");
        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isCloseTo(memoryGb, org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest
    @CsvSource({
            // Every non-N1 standard shape is 4 GiB per vCPU. Applying N1's 3.75 costed an
            // n2-standard-8 as 30 GiB of RAM instead of 32.
            "n2-standard-8,   8, 32.0,  N2",
            "e2-standard-4,   4, 16.0,  E2",
            "n2d-standard-16,16, 64.0,  N2D",
            "c2-standard-30, 30, 120.0, C2",
            "t2d-standard-2,  2, 8.0,   T2D",
            "c3-standard-4,   4, 16.0,  C3"
    })
    @DisplayName("non-N1 standard shapes are 4 GiB per vCPU")
    void standardShapes(String machineType, double vCpus, double memoryGb, String family) {
        var specs = parse(machineType);

        assertThat(specs.catalogFamily()).isEqualTo(family);
        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @ParameterizedTest
    @CsvSource({
            "n2-highmem-4,   4, 32.0",
            "e2-highmem-8,   8, 64.0",
            "c3-highmem-4,   4, 32.0"
    })
    @DisplayName("non-N1 highmem shapes are 8 GiB per vCPU")
    void highmemShapes(String machineType, double vCpus, double memoryGb) {
        var specs = parse(machineType);

        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @ParameterizedTest
    @CsvSource({
            // N2/E2 highcpu give 1 GiB per vCPU; the dense-compute families give 2.
            "n2-highcpu-8,   8,  8.0",
            "e2-highcpu-4,   4,  4.0",
            "c2d-highcpu-8,  8, 16.0",
            "c3-highcpu-4,   4,  8.0",
            "c3d-highcpu-8,  8, 16.0"
    })
    @DisplayName("highcpu memory differs between the general and dense-compute families")
    void highcpuShapes(String machineType, double vCpus, double memoryGb) {
        var specs = parse(machineType);

        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @ParameterizedTest
    @CsvSource({
            // No per-vCPU ratio predicts these, so they are tabulated.
            "m1-ultramem-40,   40,  961.0",
            "m1-megamem-96,    96,  1433.6",
            "m2-ultramem-416, 416,  11776.0",
            "m2-megamem-416,  416,  5888.0"
    })
    @DisplayName("memory-optimised shapes come from the fixed table")
    void memoryOptimisedShapes(String machineType, double vCpus, double memoryGb) {
        var specs = parse(machineType);

        assertThat(specs.recognised()).isTrue();
        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @ParameterizedTest
    @CsvSource({
            // A custom type states its own memory in MiB, so no ratio is involved.
            "n2-custom-4-16384,  4, 16.0,  N2",
            "n2d-custom-2-4096,  2, 4.0,   N2D",
            "e2-custom-8-32768,  8, 32.0,  E2"
    })
    @DisplayName("custom types are read from their own name")
    void customTypes(String machineType, double vCpus, double memoryGb, String family) {
        var specs = parse(machineType);

        assertThat(specs.recognised()).isTrue();
        assertThat(specs.catalogFamily()).isEqualTo(family);
        assertThat(specs.vCpus()).isEqualTo(vCpus);
        assertThat(specs.memoryGb()).isEqualTo(memoryGb);
    }

    @Test
    @DisplayName("a fully qualified machine type URL is accepted")
    void machineTypeUrl() {
        var specs = parse(
                "https://www.googleapis.com/compute/v1/projects/p/zones/us-central1-a/machineTypes/n2-standard-4");

        assertThat(specs.recognised()).isTrue();
        assertThat(specs.catalogFamily()).isEqualTo("N2");
        assertThat(specs.vCpus()).isEqualTo(4);
        assertThat(specs.memoryGb()).isEqualTo(16.0);
    }

    @Test
    @DisplayName("the local-SSD and bare-metal suffixes do not change the shape")
    void variantSuffixes() {
        assertThat(parse("c3-standard-8-lssd").memoryGb()).isEqualTo(32.0);
        assertThat(parse("c3-standard-8-lssd").recognised()).isTrue();
        assertThat(parse("c3d-standard-360-metal").vCpus()).isEqualTo(360);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-machine-type",
            "n2-standard",
            "n2-weirdshape-8",
            // ultramem outside the fixed table: the ratio varies with size, so there is nothing
            // safe to compute and a guess would be a large one.
            "m1-ultramem-1024"
    })
    @DisplayName("an unparseable machine type is reported as unrecognised, not guessed")
    void unrecognisedTypes(String machineType) {
        var specs = parse(machineType);

        assertThat(specs.recognised()).isFalse();
        // The old code silently substituted 1 vCPU and 4 GB here, which produced a confident price
        // for a machine whose shape was unknown.
        assertThat(specs.vCpus()).isZero();
        assertThat(specs.memoryGb()).isZero();
    }

    @Test
    @DisplayName("a null machine type does not throw")
    void nullIsSafe() {
        assertThat(parse(null).recognised()).isFalse();
    }

    @Test
    @DisplayName("case is normalised")
    void caseInsensitive() {
        var specs = parse("N2-STANDARD-4");

        assertThat(specs.recognised()).isTrue();
        assertThat(specs.catalogFamily()).isEqualTo("N2");
        assertThat(specs.memoryGb()).isEqualTo(16.0);
    }
}
