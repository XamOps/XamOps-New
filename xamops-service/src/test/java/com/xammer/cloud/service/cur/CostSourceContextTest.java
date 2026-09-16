package com.xammer.cloud.service.cur;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CostSourceContextTest {

    @AfterEach
    void tearDown() {
        CostSourceContext.clear();
    }

    @Test
    @DisplayName("Defaults to preferring the report when nothing asked otherwise")
    void defaultsToAuto() {
        assertEquals(CostSourceContext.AUTO, CostSourceContext.current());
    }

    @Test
    @DisplayName("Recognises the spellings the UI and a human might send")
    void parsesCostExplorerSpellings() {
        assertEquals(CostSourceContext.COST_EXPLORER, CostSourceContext.parse("CE"));
        assertEquals(CostSourceContext.COST_EXPLORER, CostSourceContext.parse("ce"));
        assertEquals(CostSourceContext.COST_EXPLORER, CostSourceContext.parse("COST_EXPLORER"));
        assertEquals(CostSourceContext.COST_EXPLORER, CostSourceContext.parse(" CostExplorer "));
    }

    @Test
    @DisplayName("Recognises the report-only spellings the FinOps date range sends")
    void parsesCurOnlySpellings() {
        // Sent for a custom date range, where the page has told the user the figures come
        // from the report — so a silent Cost Explorer fallback would misstate the source.
        assertEquals(CostSourceContext.CUR_ONLY, CostSourceContext.parse("CUR"));
        assertEquals(CostSourceContext.CUR_ONLY, CostSourceContext.parse("cur"));
        assertEquals(CostSourceContext.CUR_ONLY, CostSourceContext.parse("CUR_ONLY"));
        assertEquals(CostSourceContext.CUR_ONLY, CostSourceContext.parse(" CurOnly "));
    }

    @Test
    @DisplayName("Anything unrecognised falls back to the report rather than erroring")
    void unknownValuesFallBackToAuto() {
        // A typo in a URL must not silently route an account to a different source, and
        // must not fail the request either. The typos are near-misses of real values on
        // purpose: those are the ones that could plausibly be mistaken for a valid choice.
        assertEquals(CostSourceContext.AUTO, CostSourceContext.parse("CURR"));
        assertEquals(CostSourceContext.AUTO, CostSourceContext.parse("C E"));
        assertEquals(CostSourceContext.AUTO, CostSourceContext.parse("nonsense"));
        assertEquals(CostSourceContext.AUTO, CostSourceContext.parse(""));
        assertEquals(CostSourceContext.AUTO, CostSourceContext.parse(null));
    }

    @Test
    @DisplayName("clear() removes the value — these threads are pooled and reused")
    void clearRemovesTheValue() {
        CostSourceContext.set(CostSourceContext.COST_EXPLORER);
        assertEquals(CostSourceContext.COST_EXPLORER, CostSourceContext.current());

        CostSourceContext.clear();

        // peek() must be null, not AUTO: the task decorator copies the raw value, and a
        // leaked preference would send an unrelated later request to the wrong source.
        assertNull(CostSourceContext.peek());
        assertEquals(CostSourceContext.AUTO, CostSourceContext.current());
    }
}
