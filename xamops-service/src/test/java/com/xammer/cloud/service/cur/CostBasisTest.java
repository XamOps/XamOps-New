package com.xammer.cloud.service.cur;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cost basis parameter, which exists so a figure is never labelled with a basis it was not
 * computed on. Its one interesting property is that it refuses rather than defaults.
 */
class CostBasisTest {

    @Test
    @DisplayName("An absent basis is unblended — the invoice figure, which is the right default")
    void absentBasisIsUnblended() {
        assertEquals(CostBasis.UNBLENDED, CostBasis.from(null));
        assertEquals(CostBasis.UNBLENDED, CostBasis.from(""));
        assertEquals(CostBasis.UNBLENDED, CostBasis.from("   "));
    }

    @Test
    @DisplayName("Both values parse in any case")
    void bothValuesParseCaseInsensitively() {
        assertEquals(CostBasis.UNBLENDED, CostBasis.from("unblended"));
        assertEquals(CostBasis.UNBLENDED, CostBasis.from("UNBLENDED"));
        assertEquals(CostBasis.AMORTIZED, CostBasis.from("amortized"));
        assertEquals(CostBasis.AMORTIZED, CostBasis.from("Amortized"));
    }

    @Test
    @DisplayName("A typo throws rather than silently answering on the unblended basis")
    void aTypoIsNeverASilentDefault() {
        // basis=amortised answered as unblended is a figure labelled as something it is not, and
        // the label is on screen. That is the one outcome this parameter exists to prevent.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> CostBasis.from("amortised"));
        assertEquals("Expected 'unblended' or 'amortized', got 'amortised'", thrown.getMessage());
    }

    @Test
    @DisplayName("wire() round-trips through from(), so the UI's word and the parser's agree")
    void wireRoundTrips() {
        for (CostBasis basis : CostBasis.values()) {
            assertEquals(basis, CostBasis.from(basis.wire()));
        }
        assertEquals("unblended", CostBasis.UNBLENDED.wire());
        assertEquals("amortized", CostBasis.AMORTIZED.wire());
    }
}
