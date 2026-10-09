package com.xammer.billops.service;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.xammer.billops.dto.AdminDashboardDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC 24 (FR-4.6): {@link AdminDashboardDto} serializes to the same JSON field set before and after
 * the dashboard isolation change (FEAT-003).
 *
 * <p>The fixture is fully populated: every setter of the DTO and of each nested DTO gets a fixed
 * non-null value, each list gets one element ({@code AccountSummaryDto}, {@code InvoiceDto},
 * {@code TicketDto}, {@code UserDashboardDto.CostHistory} and their nested lists) and each map one
 * entry. It is serialized with {@code new ObjectMapper().findAndRegisterModules()}, and the sorted
 * field paths are compared with {@value #FIELDS_RESOURCE}: {@code a.b} for a nested field,
 * {@code list[].x} for a list element's field and {@code map.{}} for a map's data keys.
 *
 * <p>Run once with {@code -D}{@value #CAPTURE_PROPERTY}{@code =true} to (re)write the file instead of
 * comparing. The committed file was captured before {@code AdminDashboardService} was changed.
 */
class AdminDashboardDtoShapeTest {

    static final String CAPTURE_PROPERTY = "lobtext.captureDtoFields";
    private static final String FIELDS_RESOURCE = "/dashboard/admin-dashboard-fields.txt";
    private static final Path FIELDS_SOURCE = Path.of("src/test/resources/dashboard/admin-dashboard-fields.txt");
    private static final String DTO_PACKAGE = AdminDashboardDto.class.getPackageName();
    private static final int MAX_DEPTH = 6;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void theSerializedFieldSetMatchesTheCapturedOne() throws Exception {
        AdminDashboardDto dashboard = populate(AdminDashboardDto.class, 0);
        JsonNode json = mapper.readTree(mapper.writeValueAsString(dashboard));

        List<String> nulls = new ArrayList<>();
        findNulls(json, "", nulls);
        assertThat(nulls).as("the fixture sets every field").isEmpty();

        SortedSet<String> paths = new TreeSet<>();
        collectPaths(json, mapper.constructType(AdminDashboardDto.class), "", paths);
        assertThat(paths).as("field paths").contains("totalAccounts", "topSpendingAccounts[].clientName",
                "spendingByProvider.{}", "spendingTrend[].costByProvider.{}", "recentInvoices[].lineItems[].cost",
                "recentTickets[].replies[].attachments[].url");

        if (Boolean.getBoolean(CAPTURE_PROPERTY)) {
            capture(paths);
            return;
        }
        assertThat(captured()).as("AdminDashboardDto JSON field paths against %s", FIELDS_RESOURCE)
                .containsExactlyElementsOf(paths);
    }

    // ------------------------------------------------------------------ the fixture

    /** A new {@code type} with every public one-argument setter called with a fixed value. */
    private <T> T populate(Class<T> type, int depth) throws Exception {
        assertThat(depth).as("DTO nesting deeper than %s at %s", MAX_DEPTH, type.getName()).isLessThan(MAX_DEPTH);
        T bean = type.getDeclaredConstructor().newInstance();
        List<Method> setters = Arrays.stream(type.getMethods())
                .filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .sorted(Comparator.comparing(Method::getName)
                        .thenComparing(m -> m.getParameterTypes()[0].getName()))
                .toList();
        for (Method setter : setters) {
            setter.invoke(bean, sample(setter.getGenericParameterTypes()[0], depth + 1));
        }
        return bean;
    }

    private Object sample(Type type, int depth) throws Exception {
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                List<Object> list = new ArrayList<>();
                list.add(sample(args[0], depth));
                return list;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<Object, Object> map = new LinkedHashMap<>();
                map.put(sample(args[0], depth), sample(args[1], depth));
                return map;
            }
            throw new IllegalStateException("no sample value for " + type);
        }
        Class<?> c = (Class<?>) type;
        if (c == String.class) {
            return "s";
        }
        if (c == Long.class || c == long.class) {
            return 7L;
        }
        if (c == Integer.class || c == int.class) {
            return 7;
        }
        if (c == Double.class || c == double.class) {
            return 7.5;
        }
        if (c == Boolean.class || c == boolean.class) {
            return true;
        }
        if (c == BigDecimal.class) {
            return new BigDecimal("7.50");
        }
        if (c == LocalDate.class) {
            return LocalDate.of(2026, 9, 30);
        }
        if (c == LocalDateTime.class) {
            return LocalDateTime.of(2026, 9, 30, 10, 15, 30);
        }
        if (c.isEnum()) {
            return c.getEnumConstants()[0];
        }
        if (c.getName().startsWith(DTO_PACKAGE + ".")) {
            return populate(c, depth);
        }
        throw new IllegalStateException("no sample value for " + type);
    }

    // ------------------------------------------------------------------ field paths

    /**
     * Adds the sorted field paths of {@code node}. The Java type tells a map (data keys, {@code .{}})
     * from a bean (field names) and a list ({@code []}) from a value written as an array, such as a
     * date.
     */
    private void collectPaths(JsonNode node, JavaType type, String path, SortedSet<String> out) {
        if (node.isObject() && type.isMapLikeType()) {
            if (node.isEmpty()) {
                out.add(path + ".{}");
            }
            node.elements().forEachRemaining(v -> collectPaths(v, type.getContentType(), path + ".{}", out));
        } else if (node.isObject()) {
            if (node.isEmpty()) {
                out.add(path);
            }
            Map<String, JavaType> properties = propertyTypes(type);
            node.properties().forEach(e -> collectPaths(e.getValue(),
                    properties.getOrDefault(e.getKey(), mapper.constructType(Object.class)),
                    path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out));
        } else if (node.isArray() && (type.isCollectionLikeType() || type.isArrayType())) {
            if (node.isEmpty()) {
                out.add(path + "[]");
            }
            node.elements().forEachRemaining(v -> collectPaths(v, type.getContentType(), path + "[]", out));
        } else {
            out.add(path);
        }
    }

    private Map<String, JavaType> propertyTypes(JavaType type) {
        Map<String, JavaType> types = new HashMap<>();
        BeanDescription description = mapper.getSerializationConfig().introspect(type);
        for (BeanPropertyDefinition property : description.findProperties()) {
            types.put(property.getName(), property.getPrimaryType());
        }
        return types;
    }

    private static void findNulls(JsonNode node, String path, List<String> out) {
        if (node.isNull()) {
            out.add(path);
        } else if (node.isObject()) {
            node.properties().forEach(e -> findNulls(e.getValue(), path + "/" + e.getKey(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                findNulls(node.get(i), path + "/" + i, out);
            }
        }
    }

    // ------------------------------------------------------------------ the captured file

    private static void capture(SortedSet<String> paths) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# AdminDashboardDto JSON field paths (AC 24). Written by AdminDashboardDtoShapeTest with");
        lines.add("# -D" + CAPTURE_PROPERTY + "=true before AdminDashboardService was changed (FEAT-003 step 3.1).");
        lines.add("# a.b = nested field, x[] = list element, m.{} = map value under a data key.");
        lines.addAll(paths);
        Files.createDirectories(FIELDS_SOURCE.getParent());
        Files.write(FIELDS_SOURCE, lines, StandardCharsets.UTF_8);
    }

    private static List<String> captured() throws IOException {
        try (InputStream in = AdminDashboardDtoShapeTest.class.getResourceAsStream(FIELDS_RESOURCE)) {
            assertThat(in).as("%s on the test classpath; capture it with -D%s=true", FIELDS_RESOURCE, CAPTURE_PROPERTY)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }
}
