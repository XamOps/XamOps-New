package com.xammer.cloud.service.automation.workload;

import io.fabric8.kubernetes.api.model.Quantity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The <strong>only</strong> place a Kubernetes quantity string is read or written.
 *
 * <h2>Why this class exists (audit bug 3)</h2>
 *
 * The Go reference computed its targets through {@code resource.Quantity.Value()}, which returns
 * <em>whole units</em>. For CPU that means <strong>{@code 250m} rounds up to 1 core</strong> — a 4×
 * error on every sub-core container, which is most containers. It also wrote
 * {@code qty, _ := resource.ParseQuantity(...)}, discarding the parse error, so an unreadable metric
 * silently became zero.
 *
 * <p>Both failures are prevented structurally rather than fixed:
 *
 * <ul>
 *   <li><strong>One canonical unit pair, everywhere:</strong> CPU as {@code int} millicores, memory
 *       as {@code long} bytes. No engine type holds a resource value as a {@code String}, so there
 *       is nowhere for a rounding {@code Value()}-equivalent to be written.</li>
 *   <li><strong>One parse entry point that throws.</strong> There is no {@code parseOrDefault}
 *       overload and no zero-valued fallback. Every method here either returns an exact value or
 *       raises {@link QuantityParseException} naming the offending string and its field.</li>
 * </ul>
 *
 * <p>Arithmetic goes through {@link BigDecimal} with {@link RoundingMode#HALF_UP} at a single, named
 * point, so {@code 250m} parses to exactly {@code 250} and formats back to exactly {@code "250m"}.
 */
public final class Quantities {

    private static final BigDecimal MILLIS_PER_UNIT = BigDecimal.valueOf(1000);
    private static final long KI = 1024L;
    private static final long MI = 1024L * 1024L;
    private static final long GI = 1024L * 1024L * 1024L;

    private Quantities() {
    }

    /**
     * Reads a CPU quantity as integer millicores.
     *
     * <p>{@code "250m" -> 250}, {@code "1" -> 1000}, {@code "0.25" -> 250}, {@code "1500m" -> 1500}.
     *
     * @throws QuantityParseException on null, blank, unparseable or negative input
     */
    public static int cpuMillis(String quantity) {
        return cpuMillis(quantity, "a CPU quantity");
    }

    public static int cpuMillis(String quantity, String field) {
        BigDecimal cores = baseUnits(quantity, field);
        BigDecimal millis = cores.multiply(MILLIS_PER_UNIT).setScale(0, RoundingMode.HALF_UP);
        if (millis.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new QuantityParseException(field, quantity, "CPU value is implausibly large");
        }
        return millis.intValueExact();
    }

    /**
     * Reads a memory quantity as integer bytes.
     *
     * <p>{@code "250Mi" -> 262144000}, {@code "1Gi" -> 1073741824}, {@code "64Mi" -> 67108864}.
     *
     * @throws QuantityParseException on null, blank, unparseable or negative input
     */
    public static long memBytes(String quantity) {
        return memBytes(quantity, "a memory quantity");
    }

    public static long memBytes(String quantity, String field) {
        BigDecimal bytes = baseUnits(quantity, field);
        BigDecimal whole = bytes.setScale(0, RoundingMode.HALF_UP);
        if (whole.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new QuantityParseException(field, quantity, "memory value is implausibly large");
        }
        return whole.longValueExact();
    }

    /**
     * Formats millicores back as a Kubernetes CPU quantity.
     *
     * <p>Always the {@code m} suffix, even for whole cores ({@code 1000 -> "1000m"}), because a
     * single representation round-trips exactly and needs no decimal formatting — and a decimal
     * representation is where the original rounding bug lived.
     */
    public static String formatCpu(int millis) {
        return millis + "m";
    }

    /**
     * Formats bytes back as a Kubernetes memory quantity: the largest binary suffix that divides
     * exactly, otherwise a plain byte count.
     *
     * <p>{@code 262144000 -> "250Mi"}, {@code 1073741824 -> "1Gi"}, {@code 1000 -> "1000"}. Never an
     * approximation: an inexact suffix would make the formatted value differ from the decided one.
     */
    public static String formatMem(long bytes) {
        if (bytes != 0 && bytes % GI == 0) {
            return (bytes / GI) + "Gi";
        }
        if (bytes != 0 && bytes % MI == 0) {
            return (bytes / MI) + "Mi";
        }
        if (bytes != 0 && bytes % KI == 0) {
            return (bytes / KI) + "Ki";
        }
        return Long.toString(bytes);
    }

    /** Bytes as whole MiB, rounded half-up — the API's wire unit for memory. */
    public static long bytesToMi(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(MI), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static long miToBytes(long mebibytes) {
        return mebibytes * MI;
    }

    /**
     * The single parse point. {@link Quantity#getAmountInBytes(Quantity)} returns base units — cores
     * for CPU, bytes for memory — which is what makes one helper serve both resources.
     */
    private static BigDecimal baseUnits(String quantity, String field) {
        if (quantity == null || quantity.isBlank()) {
            throw new QuantityParseException(field, quantity, "the value is absent");
        }
        BigDecimal amount;
        try {
            amount = Quantity.getAmountInBytes(Quantity.parse(quantity.trim()));
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException e) {
            throw new QuantityParseException(field, quantity,
                    "it is not a Kubernetes quantity (expected forms: 250m, 1, 0.25, 64Mi, 1Gi)", e);
        }
        if (amount == null) {
            throw new QuantityParseException(field, quantity, "it has no numeric amount");
        }
        if (amount.signum() < 0) {
            throw new QuantityParseException(field, quantity,
                    "a negative resource quantity is not expressible in Kubernetes");
        }
        return amount;
    }
}
