package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Golden-fixture plumbing (design-ledger.md §14.1): {@code src/test/resources/ledger/golden/<name>/
 * {inputs.json, entries.expected.json, lines.expected.json}}. {@code inputs.json} holds the day inputs of one
 * group (or several); entries are the calculator's output, lines come from this <em>test-only</em> line
 * builder, a thin wrapper over {@link StatementLineMath}, until Phase 32 ships the real statement
 * builder.
 */
final class GoldenFixtures {
    static final Path ROOT = Path.of("src/test/resources/ledger/golden");

    record GoldenInputs(List<CalcInputs> days) {}

    /** What a fixture produces: the entries (one per group and day that earns one) and the lines per group-month. */
    record Output(List<EntryCalc> entries, List<StatementLineMath.Line> lines) {}

    private GoldenFixtures() {}

    static List<String> names() throws IOException {
        try (Stream<Path> dirs = Files.list(ROOT)) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static GoldenInputs inputs(String name) throws IOException {
        return CanonicalJson.read(Files.readString(ROOT.resolve(name).resolve("inputs.json")), GoldenInputs.class);
    }

    static Output run(GoldenInputs inputs) {
        SavingsCalculator calculator = new SavingsCalculator();
        List<EntryCalc> entries = new ArrayList<>();
        Map<String, List<EntryCalc>> byGroupMonth = new LinkedHashMap<>();
        Map<String, Boolean> contract = new LinkedHashMap<>();
        for (CalcInputs day : inputs.days()) {
            calculator.calculate(day).ifPresent(e -> {
                entries.add(e);
                String key = e.groupId() + "|" + e.statementMonth();
                byGroupMonth.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
                contract.merge(key, day.contractCovers(), Boolean::logicalAnd);
            });
        }
        List<StatementLineMath.Line> lines = new ArrayList<>();
        byGroupMonth.forEach((key, list) -> lines.add(StatementLineMath.build(list, contract.get(key))));
        return new Output(entries, lines);
    }

    static String read(String name, String file) throws IOException {
        return Files.readString(ROOT.resolve(name).resolve(file)).stripTrailing();
    }

    static void write(String name, String file, String text) {
        try {
            Files.writeString(ROOT.resolve(name).resolve(file), text + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
