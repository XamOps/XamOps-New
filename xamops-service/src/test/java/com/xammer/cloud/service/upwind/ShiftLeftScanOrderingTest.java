package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selection of the newest shift-left scans.
 *
 * Upwind's shift-left search accepts no sort field and returns its stream oldest-first, so asking
 * for N scans previously returned the N *oldest* and dropped everything newer. That is what made
 * the Code tab's scans-per-day chart wrong: it plotted an empty final week because those scans
 * were never in the payload.
 *
 * These tests pin the selection rule directly, without needing a live Upwind org.
 */
class ShiftLeftScanOrderingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode scan(String createTime) {
        return mapper.createObjectNode().put("create_time", createTime);
    }

    /** Mirrors the sort-then-take applied in UpwindApiClient.searchShiftLeftScans. */
    private List<JsonNode> newest(List<JsonNode> all, int maxItems) {
        List<JsonNode> copy = new ArrayList<>(all);
        copy.sort(Comparator.comparingLong(this::createMillis).reversed());
        return copy.size() > maxItems ? copy.subList(0, maxItems) : copy;
    }

    private long createMillis(JsonNode n) {
        String raw = n.path("create_time").asText(null);
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return java.time.Instant.parse(raw).toEpochMilli();
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
            } catch (Exception e2) {
                return 0L;
            }
        }
    }

    @Test
    void keepsTheNewestScansWhenTheStreamArrivesOldestFirst() {
        // Upstream order: oldest to newest, which is what Upwind actually returns.
        List<JsonNode> stream = List.of(
                scan("2026-07-14T10:00:00Z"),
                scan("2026-07-15T10:00:00Z"),
                scan("2026-08-10T10:00:00Z"),
                scan("2026-08-13T10:00:00Z"),
                scan("2026-08-14T10:00:00Z"));

        List<JsonNode> selected = newest(stream, 2);

        // Truncating the head of the stream, as the old code did, would have yielded the two
        // July scans and lost the whole of August.
        assertThat(selected).extracting(n -> n.get("create_time").asText())
                .containsExactly("2026-08-14T10:00:00Z", "2026-08-13T10:00:00Z");
    }

    @Test
    void returnsEverythingNewestFirstWhenUnderTheLimit() {
        List<JsonNode> stream = List.of(
                scan("2026-08-01T00:00:00Z"),
                scan("2026-08-03T00:00:00Z"),
                scan("2026-08-02T00:00:00Z"));

        assertThat(newest(stream, 200)).extracting(n -> n.get("create_time").asText())
                .containsExactly("2026-08-03T00:00:00Z", "2026-08-02T00:00:00Z", "2026-08-01T00:00:00Z");
    }

    @Test
    void sortsScansWithNoTimestampLastRatherThanDroppingThem() {
        // Roughly one scan in nine has fields missing upstream; an unparseable create_time must
        // not knock a real scan out of the selection.
        List<JsonNode> stream = new ArrayList<>();
        stream.add(mapper.createObjectNode());                 // no create_time at all
        stream.add(scan(""));                                  // present but empty
        stream.add(scan("not-a-date"));                         // unparseable
        stream.add(scan("2026-08-14T10:00:00Z"));

        List<JsonNode> selected = newest(stream, 4);

        assertThat(selected.get(0).path("create_time").asText()).isEqualTo("2026-08-14T10:00:00Z");
        assertThat(selected).hasSize(4);
    }

    @Test
    void acceptsOffsetTimestampsNotJustUtc() {
        // Instant.parse rejects a numeric offset, so the fallback parser has to handle it or a
        // whole region's scans would sort to the bottom as if they had no date.
        List<JsonNode> stream = List.of(
                scan("2026-08-14T10:00:00+05:30"),   // 04:30Z
                scan("2026-08-14T06:00:00Z"));

        assertThat(newest(stream, 1)).extracting(n -> n.get("create_time").asText())
                .containsExactly("2026-08-14T06:00:00Z");
    }

    @Test
    void pageAndBudgetCeilingsAreBoundedSoTheCrawlCannotRunAway() {
        // Paging replaced an early exit, so the bounds are what stop a large tenant turning this
        // into an unbounded crawl on a request thread.
        assertThat(UpwindApiClient.SHIFT_LEFT_MAX_PAGES).isBetween(1, 100);
        assertThat(UpwindApiClient.SHIFT_LEFT_BUDGET_MS).isBetween(1_000L, 60_000L);
        // Must be able to read past the largest window a caller can ask for (service caps at 500).
        assertThat(UpwindApiClient.SHIFT_LEFT_MAX_PAGES * UpwindApiClient.SEARCH_PAGE_SIZE)
                .isGreaterThan(500);
    }
}
