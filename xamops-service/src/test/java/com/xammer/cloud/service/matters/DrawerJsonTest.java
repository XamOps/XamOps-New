package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.6: the one meaning of text, texts and epoch seconds; review fix R7's extensionOf. */
class DrawerJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("stringValue trims JSON strings; blanks, numbers, booleans, null and containers are null")
    void stringValue() {
        assertThat(DrawerJson.stringValue(parse("\"  x  \""))).isEqualTo("x");
        assertThat(DrawerJson.stringValue(parse("\"\""))).isNull();
        assertThat(DrawerJson.stringValue(parse("\"   \""))).isNull();
        assertThat(DrawerJson.stringValue(parse("121863"))).isNull();
        assertThat(DrawerJson.stringValue(parse("true"))).isNull();
        assertThat(DrawerJson.stringValue(parse("null"))).isNull();
        assertThat(DrawerJson.stringValue(parse("{\"a\": \"b\"}"))).isNull();
        assertThat(DrawerJson.stringValue(parse("[\"a\"]"))).isNull();
        assertThat(DrawerJson.stringValue(null)).isNull();
    }

    @Test
    @DisplayName("string reads one field; a missing field or a non-object is null")
    void string() {
        JsonNode node = parse("{\"name\": \" Field Notes \", \"size\": 12}");
        assertThat(DrawerJson.string(node, "name")).isEqualTo("Field Notes");
        assertThat(DrawerJson.string(node, "size")).isNull();
        assertThat(DrawerJson.string(node, "missing")).isNull();
        assertThat(DrawerJson.string(parse("\"text\""), "name")).isNull();
        assertThat(DrawerJson.string(null, "name")).isNull();
    }

    @Test
    @DisplayName("strings skips non-text, stops at the cap and drops over-long values instead of cutting them")
    void strings() {
        JsonNode array = parse("[\" a \", null, 7, \"\", \"bbbb\", \"cc\", \"d\", \"e\"]");
        assertThat(DrawerJson.strings(array, 10, 3)).containsExactly("a", "cc", "d", "e");
        assertThat(DrawerJson.strings(array, 2, 3)).containsExactly("a", "cc");
        assertThat(DrawerJson.strings(array, 10, 4)).containsExactly("a", "bbbb", "cc", "d", "e");
        assertThat(DrawerJson.strings(parse("{\"a\": \"b\"}"), 10, 10)).isEmpty();
        assertThat(DrawerJson.strings(parse("\"a\""), 10, 10)).isEmpty();
        assertThat(DrawerJson.strings(null, 10, 10)).isEmpty();
    }

    @Test
    @DisplayName("epochSeconds floors decimals, divides milliseconds, parses ISO-8601 and rejects anything else")
    void epochSeconds() {
        assertThat(DrawerJson.epochSeconds(parse("1789725004.377114"))).isEqualTo(1789725004L);
        assertThat(DrawerJson.epochSeconds(parse("1789725004"))).isEqualTo(1789725004L);
        assertThat(DrawerJson.epochSeconds(parse("1789725004377"))).isEqualTo(1789725004L);
        assertThat(DrawerJson.epochSeconds(parse("1789725004377.9"))).isEqualTo(1789725004L);
        assertThat(DrawerJson.epochSeconds(parse("1000000000000"))).isEqualTo(1_000_000_000_000L);

        long expected = Instant.parse("2026-09-10T09:15:00Z").getEpochSecond();
        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10T09:15:00+00:00\""))).isEqualTo(expected);
        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10T14:45:00+05:30\""))).isEqualTo(expected);
        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10T09:15:00Z\""))).isEqualTo(expected);
        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10T09:15:00.250Z\""))).isEqualTo(expected);

        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10 09:15\""))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("\"2026-09-10T09:15:00\""))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("\"1789725004\""))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("\"\""))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("true"))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("null"))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("{\"value\": 1}"))).isNull();
        assertThat(DrawerJson.epochSeconds(parse("{}").path("missing"))).isNull();
        assertThat(DrawerJson.epochSeconds(null)).isNull();
    }

    @Test
    @DisplayName("extensionOf follows the frontend rule: the basename's last dot, lower-cased (R7)")
    void extensionOf() {
        assertThat(DrawerJson.extensionOf("a/b.PDF")).isEqualTo("pdf");
        assertThat(DrawerJson.extensionOf("c:\\x.Docx")).isEqualTo("docx");
        assertThat(DrawerJson.extensionOf("notes")).isNull();
        assertThat(DrawerJson.extensionOf("trailing.")).isNull();
        assertThat(DrawerJson.extensionOf(".env")).isEqualTo("env");
        assertThat(DrawerJson.extensionOf("v1.2 notes")).isEqualTo("2 notes");
        assertThat(DrawerJson.extensionOf("archive.tar.GZ")).isEqualTo("gz");
        assertThat(DrawerJson.extensionOf("dir.d/readme")).isNull();
        assertThat(DrawerJson.extensionOf("dir.d\\readme")).isNull();
        assertThat(DrawerJson.extensionOf("")).isNull();
        assertThat(DrawerJson.extensionOf(null)).isNull();
    }
}
