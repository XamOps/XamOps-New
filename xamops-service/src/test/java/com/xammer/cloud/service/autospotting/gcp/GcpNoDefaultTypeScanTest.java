package com.xammer.cloud.service.autospotting.gcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-PRC-02: a source scan of the v2 GCP package finds no {@code n1-standard-1} default and no
 * {@code -spot} name inference (the provisioning model comes from {@code scheduling.provisioningModel}).
 */
class GcpNoDefaultTypeScanTest {
    private static final Path SOURCE = Path.of("src/main/java/com/xammer/cloud/service/autospotting/gcp");

    @Test
    void noDefaultMachineTypeAndNoSpotNameInference() throws IOException {
        assertThat(SOURCE).isDirectory();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).isNotEmpty();
            for (Path p : sources) {
                String text = Files.readString(p);
                // Comments may name what is forbidden; only code is scanned.
                String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                assertThat(code).as(p.getFileName() + " defaults a machine type").doesNotContain("n1-standard-1");
                assertThat(code).as(p.getFileName() + " infers Spot from a name")
                        .doesNotContain("\"-spot\"")
                        .doesNotContain("endsWith(\"-spot")
                        .doesNotContain("contains(\"-spot")
                        .doesNotContain("contains(\"spot-");
            }
        }
    }
}
