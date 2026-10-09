package com.xammer.cloud.domain.type;

import jakarta.persistence.EntityManagerFactory;

import java.util.Locale;

/**
 * Storage format that {@link LegacyLobTextType} writes into the legacy large-object TEXT columns.
 *
 * <p>The property is {@value #PROPERTY}, named the same in billops and xamops-service; the
 * environment variable is {@code XAMMER_LOBTEXT_WRITEMODE}. Absent or blank means
 * {@link #LEGACY}. The value is trimmed and case-insensitive, and anything other than
 * {@code legacy} or {@code inline} fails {@code EntityManagerFactory} bootstrap.
 *
 * <p>This file is byte-identical in both services ({@code LobTextSourceParityTest}).
 */
public enum LobTextWriteMode {

    /** Write a new large object per changed value and store its OID as decimal text. */
    LEGACY,

    /** Store the value itself as plain text. */
    INLINE;

    /** Hibernate setting read by {@link LegacyLobTextType}, bridged from the Spring property. */
    public static final String PROPERTY = "xammer.lob-text.write-mode";

    /**
     * Parses a configured value.
     *
     * @param raw the configured value, or {@code null}
     * @return {@link #LEGACY} for {@code null} or blank, otherwise the named mode
     * @throws IllegalArgumentException for any other value
     */
    public static LobTextWriteMode parse(Object raw) {
        if (raw == null) {
            return LEGACY;
        }
        String value = raw.toString().trim();
        if (value.isEmpty()) {
            return LEGACY;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "legacy":
                return LEGACY;
            case "inline":
                return INLINE;
            default:
                throw new IllegalArgumentException(
                        PROPERTY + " must be 'legacy' or 'inline', got '" + raw + "'");
        }
    }

    /**
     * The mode the mapped {@link LegacyLobTextType} instances use: the single source of truth
     * for Java code that needs it. It parses the Hibernate setting that the type saw.
     */
    public static LobTextWriteMode effective(EntityManagerFactory emf) {
        return parse(emf.getProperties().get(PROPERTY));
    }
}
