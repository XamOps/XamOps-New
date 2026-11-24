package com.xammer.cloud.service.matters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The XamLens sample mask (build 9): nobody sees a raw sample value. Every sample that leaves xamops-service
 * goes through {@link #mask} (the half rule) or, for a secret-type entity, {@link #maskSecret}.
 *
 * <p>The half rule works on code points: values under 4 code points are fully masked with {@link #MASK};
 * otherwise at most half of the code points stay visible. A Matters {@code <<…>>} frame is kept and the rule
 * applies to its inside. Digit-only values (phones, cards, IPs) keep their last digits; text keeps separators,
 * then the first character of each word, then up to half of each word. The vendor's {@code *} becomes
 * {@link #MASK}. The rule is deterministic and idempotent: masking a masked value returns it unchanged.
 *
 * <p>This class is the source of truth; {@code frontend-app/src/utils/sampleMask.ts} is its twin for the
 * display layer and demo data.
 */
public final class SampleMask {
    /** The mask character, U+2022. */
    public static final char MASK = '\u2022';

    private static final String SEPARATORS = " \t\n\r.,-_@+/\\:;()[]{}<>|#";
    private static final Set<String> SECRET_LABELS = Set.of("SECRETS", "SECRET", "IT SECURITY");
    private static final Set<String> SECRET_NAME_WORDS = Set.of("KEY", "KEYS", "TOKEN", "TOKENS", "SECRET",
            "SECRETS", "PASSWORD", "PASSWORDS", "PASSWD", "PWD", "CREDENTIAL", "CREDENTIALS", "APIKEY");
    private static final int SECRET_VISIBLE = 4;

    private SampleMask() {
    }

    /** The half rule; null stays null. */
    public static String mask(String value) {
        return apply(value, false);
    }

    /** The secret rule: at most the first 4 code points visible, the rest masked; null stays null. */
    public static String maskSecret(String value) {
        return apply(value, true);
    }

    /** {@link #mask} of each element in order, nulls dropped; null gives an empty list. Unmodifiable. */
    public static List<String> maskAll(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null) {
                out.add(mask(value));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Whether an entity's samples use the secret rule: a label that normalises to SECRETS, SECRET or
     * IT SECURITY, or an entity name holding a key, token, secret, password or credential word. Null-safe.
     */
    public static boolean isSecret(String entityName, Collection<String> labels) {
        if (labels != null) {
            for (String label : labels) {
                if (label != null && SECRET_LABELS.contains(normalise(label))) {
                    return true;
                }
            }
        }
        if (entityName == null) {
            return false;
        }
        for (String word : normalise(entityName).split(" ")) {
            if (SECRET_NAME_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Trim, upper-case, every run of characters other than A-Z / 0-9 to one space, trim. */
    private static String normalise(String text) {
        String upper = text.trim().toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(upper.length());
        boolean gap = false;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                if (gap && out.length() > 0) {
                    out.append(' ');
                }
                gap = false;
                out.append(c);
            } else {
                gap = true;
            }
        }
        return out.toString();
    }

    private static String apply(String value, boolean secret) {
        if (value == null) {
            return null;
        }
        int[] cps = value.codePoints().map(cp -> cp == '*' ? MASK : cp).toArray();
        int n = cps.length;
        if (n >= 4 && cps[0] == '<' && cps[1] == '<' && cps[n - 2] == '>' && cps[n - 1] == '>') {
            int[] inner = new int[n - 4];
            System.arraycopy(cps, 2, inner, 0, n - 4);
            int[] masked = secret ? secretCore(inner) : core(inner);
            return "<<" + build(masked) + ">>";
        }
        return build(secret ? secretCore(cps) : core(cps));
    }

    private static String build(int[] cps) {
        StringBuilder out = new StringBuilder(cps.length);
        for (int cp : cps) {
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean isSeparator(int cp) {
        return cp < 128 && SEPARATORS.indexOf(cp) >= 0;
    }

    private static boolean isDigit(int cp) {
        return cp >= '0' && cp <= '9';
    }

    private static int[] allMasked(int n) {
        int[] out = new int[n];
        Arrays.fill(out, MASK);
        return out;
    }

    private static int[] secretCore(int[] s) {
        int n = s.length;
        if (n < 4) {
            return allMasked(n);
        }
        int keep = Math.min(SECRET_VISIBLE, n / 2);
        int[] out = allMasked(n);
        System.arraycopy(s, 0, out, 0, keep);
        return out;
    }

    private static int[] core(int[] s) {
        int n = s.length;
        if (n < 4) {
            return allMasked(n);
        }
        int masks = 0;
        int digits = 0;
        boolean numericOnly = true;
        for (int cp : s) {
            if (cp == MASK) {
                masks++;
            } else if (isDigit(cp)) {
                digits++;
            } else if (!isSeparator(cp)) {
                numericOnly = false;
            }
        }
        if (masks >= (n + 1) / 2) {
            return s.clone();
        }
        boolean[] visible = new boolean[n];
        int budget = n / 2;
        if (numericOnly && digits >= 4) {
            int keepDigits = Math.min(4, digits / 2);
            for (int i = n - 1; i >= 0 && keepDigits > 0 && budget > 0; i--) {
                if (isDigit(s[i])) {
                    visible[i] = true;
                    keepDigits--;
                    budget--;
                }
            }
            for (int i = n - 1; i >= 0 && budget > 0; i--) {
                if (isSeparator(s[i])) {
                    visible[i] = true;
                    budget--;
                }
            }
        } else {
            for (int i = 0; i < n && budget > 0; i++) {
                if (isSeparator(s[i])) {
                    visible[i] = true;
                    budget--;
                }
            }
            List<int[]> words = words(s);
            for (int[] word : words) {
                if (budget == 0) {
                    break;
                }
                if (word[1] - word[0] >= 2) {
                    visible[word[0]] = true;
                    budget--;
                }
            }
            for (int[] word : words) {
                int len = word[1] - word[0];
                int shown = len >= 2 ? 1 : 0;
                for (int i = word[0] + shown; i < word[1] && shown < len / 2 && budget > 0; i++) {
                    visible[i] = true;
                    shown++;
                    budget--;
                }
            }
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = visible[i] ? s[i] : MASK;
        }
        return out;
    }

    /** Maximal runs of non-separators as [start, end) index pairs, left to right. */
    private static List<int[]> words(int[] s) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= s.length; i++) {
            boolean sep = i == s.length || isSeparator(s[i]);
            if (sep && start >= 0) {
                out.add(new int[]{start, i});
                start = -1;
            } else if (!sep && start < 0) {
                start = i;
            }
        }
        return out;
    }
}
