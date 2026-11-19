package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a template's machine type to a priceable shape (design-gcp.md §6.4): vCPU and memory from
 * {@code machineTypes.get} (cached 24 h per project, zone and type), custom types parsed from their
 * name ({@code n2-custom-8-32768}, {@code -ext} for extended memory), and shared-core types billed
 * from {@link GcpSharedCoreTable} and never from {@code guestCpus}. There is no default type: an
 * unreadable type is a failed read, and the caller shows "Not priced".
 */
@Component
public class GcpMachineShapes {
    private static final Pattern CUSTOM = Pattern.compile("^(?:([a-z][a-z0-9]*)-)?custom-(\\d+)-(\\d+)(-ext)?$");
    private static final BigDecimal MB_PER_GIB = BigDecimal.valueOf(1024);

    /**
     * A machine shape. {@code billedVcpu} is null for a shared-core type missing from the table;
     * {@code guestCpus} is the API's count and is used only for quota, never for price.
     */
    public record Shape(String name, String series, int guestCpus, BigDecimal billedVcpu, BigDecimal memoryGiB,
                        boolean sharedCore) {
        /** A shared-core type with no row in {@code gcp-shared-core-v1}. */
        public boolean sharedCoreUnsupported() {
            return sharedCore && billedVcpu == null;
        }
    }

    private final TtlCache<String, Shape> cache;
    private final GcpSharedCoreTable table;

    @Autowired
    public GcpMachineShapes() {
        this(Clock.systemUTC(), GcpSharedCoreTable.v1());
    }

    GcpMachineShapes(Clock clock, GcpSharedCoreTable table) {
        this.cache = new TtlCache<>(Duration.ofHours(24), clock);
        this.table = table;
    }

    /** The shape of {@code machineType} in {@code zone}. Throws {@link GcpReadException} when it cannot be read. */
    public Shape shape(GcpReadOnlyView view, String zone, String machineType) {
        String type = shortName(machineType);
        Shape custom = parseCustom(type);
        if (custom != null) {
            return custom;
        }
        if (zone == null || zone.isBlank()) {
            throw new GcpReadException("machineTypes.get", ErrorKind.VALIDATION);
        }
        return cache.get(view.project() + "|" + zone + "|" + type, () -> read(view, zone, type));
    }

    static String shortName(String machineType) {
        if (machineType == null) {
            return null;
        }
        int slash = machineType.lastIndexOf('/');
        return slash < 0 ? machineType : machineType.substring(slash + 1);
    }

    /** The machine series: the prefix of the type name; a bare {@code custom-*} type is N1. */
    public static String seriesOf(String type) {
        if (type == null) {
            return null;
        }
        Matcher m = CUSTOM.matcher(type);
        if (m.matches()) {
            return m.group(1) == null ? "n1" : m.group(1);
        }
        int dash = type.indexOf('-');
        return dash < 0 ? type : type.substring(0, dash);
    }

    private Shape parseCustom(String type) {
        if (type == null) {
            return null;
        }
        Matcher m = CUSTOM.matcher(type);
        if (!m.matches()) {
            return null;
        }
        int vcpu = Integer.parseInt(m.group(2));
        BigDecimal gib = new BigDecimal(m.group(3)).divide(MB_PER_GIB, 6, RoundingMode.HALF_UP);
        return new Shape(type, seriesOf(type), vcpu, BigDecimal.valueOf(vcpu), gib, false);
    }

    private Shape read(GcpReadOnlyView view, String zone, String type) {
        JsonNode mt = view.reads().getMachineType(zone, type);
        if (!mt.hasNonNull("guestCpus") || !mt.hasNonNull("memoryMb")) {
            throw new GcpReadException("machineTypes.get", ErrorKind.VALIDATION);
        }
        int guest = mt.path("guestCpus").asInt();
        BigDecimal gib = BigDecimal.valueOf(mt.path("memoryMb").asLong()).divide(MB_PER_GIB, 6, RoundingMode.HALF_UP);
        boolean shared = mt.path("isSharedCpu").asBoolean(false);
        BigDecimal billed = shared ? table.billedVcpu(type).orElse(null) : BigDecimal.valueOf(guest);
        return new Shape(type, seriesOf(type), guest, billed, gib, shared);
    }
}
