package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.MoveType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract between the Go analyzer (xamops-autoscaler internal/consolidation) and
 * {@link ConsolidationPlanDto}: every golden plan the Go tests pin must deserialize strictly (no
 * unknown, missing or null-for-primitive field) and round-trip to the same JSON tree.
 */
class ConsolidationPlanContractTest {

    /** Surefire runs with basedir = xamops-service. */
    private static final Path GOLDEN = Paths.get("..", "xamops-autoscaler", "internal", "consolidation",
            "testdata", "golden");

    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);

    /** 40 and 40.0 are the same number: Go drops a whole double's ".0", Jackson writes it. */
    private static final Comparator<JsonNode> NUMERIC_EQUAL = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    private static List<Path> goldenPlans() throws IOException {
        assertThat(GOLDEN).as("golden plan directory %s", GOLDEN.toAbsolutePath()).isDirectory();
        try (Stream<Path> files = Files.list(GOLDEN)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".plan.json")).sorted().toList();
        }
    }

    @Test
    void everyGoldenPlanDeserializesStrictlyAndRoundTrips() throws IOException {
        List<Path> files = goldenPlans();
        assertThat(files).as("FEAT-001 golden plans").isNotEmpty();
        for (Path file : files) {
            String json = Files.readString(file);
            ConsolidationPlanDto plan = STRICT.readValue(json, ConsolidationPlanDto.class);

            assertThat(plan.schemaVersion()).as(file.toString()).isEqualTo(1);
            assertThat(plan.mode()).as(file.toString()).isEqualTo("analysis");
            assertThat(plan.moves()).as(file.toString())
                    .allSatisfy(m -> assertThat(m.type()).isIn(EnumSet.of(MoveType.EMPTY, MoveType.DRAIN)));
            ConsolidationPlanDto.Summary s = plan.summary();
            assertThat(s.empty()).as(file.toString())
                    .isEqualTo(plan.moves().stream().filter(m -> m.type() == MoveType.EMPTY).count());
            assertThat(s.drain()).as(file.toString())
                    .isEqualTo(plan.moves().stream().filter(m -> m.type() == MoveType.DRAIN).count());
            assertThat(s.blocked()).as(file.toString()).isEqualTo(plan.blocked().size());
            assertThat(s.kept()).as(file.toString()).isEqualTo(plan.kept().size());
            assertThat(s.nodesEvaluated()).as(file.toString())
                    .isEqualTo(plan.moves().size() + plan.blocked().size() + plan.kept().size());

            JsonNode original = STRICT.readTree(json);
            JsonNode roundTripped = STRICT.readTree(STRICT.writeValueAsString(plan));
            assertThat(original.equals(NUMERIC_EQUAL, roundTripped))
                    .as("%s round-trips:%n%s%nvs%n%s", file, original, roundTripped).isTrue();
        }
    }

    @Test
    void aReplaceMoveIsNotAValue() throws IOException {
        String json = Files.readString(GOLDEN.resolve("empty-and-drain.plan.json"));
        String replace = json.replace("\"type\": \"EMPTY\"", "\"type\": \"REPLACE\"");
        assertThat(replace).contains("\"REPLACE\"");
        assertThatThrownBy(() -> STRICT.readValue(replace, ConsolidationPlanDto.class))
                .isInstanceOf(InvalidFormatException.class);
        assertThat(MoveType.values()).containsExactly(MoveType.EMPTY, MoveType.DRAIN);
    }
}
