package com.xammer.cloud.domain.type;

import com.xammer.cloud.domain.CachedData;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Keeps every read and write of the legacy large-object TEXT columns behind
 * {@link LegacyLobTextType} (design D2, §7.3):
 * <ul>
 *   <li>by reflection, each mapped attribute carries the type, its key-column parameter, the
 *       unchanged {@code @Column}, class-level {@code @DynamicUpdate} and the OID-shape callback;</li>
 *   <li>by a scan of {@code src/main/java} (lines starting with {@code //}, {@code /*} or {@code *}
 *       are skipped): (a) no {@code @Query} outside the allowlist names these columns or attributes,
 *       and (b) nothing uses the client large-object API or unlinks objects, and no file but
 *       {@code LegacyLobTextType.java} touches large objects at all ({@code lo_get},
 *       {@code lo_from_bytea}, {@code LargeObjectManager}, {@code getClob}, {@code setClob},
 *       {@code findLargeObjectText}), except the {@code lo_get} reads of the ticket-chat search
 *       queries named in {@code LARGE_OBJECT_READ_ALLOWLIST}.</li>
 * </ul>
 */
class LobTextMappingGuardTest {

    /** One guarded attribute: entity, field, key-column parameter, and whether the column is NOT NULL. */
    private record Guarded(Class<?> entity, String field, String keyColumn, boolean notNull) {
    }

    private static final List<Guarded> GUARDED = List.of(
            new Guarded(CachedData.class, "jsonData", "cache_key", false));

    private static final List<String> COLUMN_NAMES = List.of(
            "json_data", "schedule_json", "encrypted_pat", "encrypted_token",
            "jsonData", "scheduleJson", "encryptedPat", "encryptedToken");

    /** {@code <file name without .java>#<method>} of the queries allowed to name these columns. */
    private static final Set<String> QUERY_ALLOWLIST = Set.of();

    /** Rule (b), every file: nothing unlinks objects or uses the client large-object API (AC 16). */
    private static final List<String> BANNED_TOKENS = List.of("lo_unlink", "LargeObjectManager", "setClob(");

    /** Rule (b), design §7.3 search 3: any large-object access, allowed only in the type itself. */
    private static final Pattern LARGE_OBJECT_ACCESS =
            Pattern.compile("lo_get|lo_from_bytea|LargeObjectManager|getClob|setClob|findLargeObjectText");
    private static final Path LARGE_OBJECT_ACCESS_OWNER = Path.of("com/xammer/cloud/domain/type/LegacyLobTextType.java");

    /**
     * {@code <file name without .java>#<method>} of the {@code @Query} methods allowed to read a
     * legacy large object with {@code lo_get}, keyed exactly like {@link #QUERY_ALLOWLIST}.
     *
     * <p>Exempt: the four BillOps ticket-chat search queries — {@code TicketRepository}'s
     * {@code findTicketIdsByDescriptionContaining} and {@code findDescriptionMatches} over
     * {@code ticket.description}, and {@code TicketReplyRepository}'s
     * {@code findTicketIdsByReplyMessageContaining} and {@code findReplyMatches} over
     * {@code ticket_reply.message}. Commit 249f6158f ("ticket chat search&page jump") added them.
     *
     * <p>They are read-only: every one is a plain {@code SELECT} under
     * {@code @Transactional(readOnly = true)} that never creates, writes, truncates or unlinks a
     * large object. They need {@code lo_get} because both columns are {@code @Lob} over a
     * PostgreSQL {@code oid}, so the column holds a pointer into {@code pg_largeobject} rather
     * than the text; a LIKE has to run against the decoded bytes
     * ({@code lower(convert_from(lo_get(..), 'UTF8')) LIKE ..}) or it compares the OID number and
     * silently matches nothing.
     *
     * <p>Before adding an entry: this exemption skips nothing but {@code lo_get}, and only on the
     * lines of the named method's own {@code @Query}, so {@link #BANNED_TOKENS} and every
     * write-side token stay banned here too. Add one only for a read-only {@code SELECT} that
     * cannot avoid large-object access, that skips orphaned OIDs (an {@code EXISTS} over
     * {@code pg_largeobject_metadata} — {@code lo_get} throws on a missing OID and aborts the
     * whole scan), and that runs inside a transaction (PostgreSQL refuses to read a large object
     * in auto-commit mode, and Spring Data does not wrap custom {@code @Query} methods).
     * Anything that writes a large object belongs behind {@link LegacyLobTextType}, not here.
     */
    private static final Set<String> LARGE_OBJECT_READ_ALLOWLIST = Set.of(
            "TicketRepository#findTicketIdsByDescriptionContaining",
            "TicketRepository#findDescriptionMatches",
            "TicketReplyRepository#findTicketIdsByReplyMessageContaining",
            "TicketReplyRepository#findReplyMatches");

    /** The one large-object token {@link #LARGE_OBJECT_READ_ALLOWLIST} can exempt: a read. */
    private static final String ALLOWLISTED_LARGE_OBJECT_READ = "lo_get";

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Pattern QUERY_ANNOTATION = Pattern.compile("@(?:Query|NativeQuery)\\b");
    private static final Pattern ANNOTATION_NAME = Pattern.compile("@[\\w.]+");
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*$");

    @Test
    void everyGuardedAttributeIsMappedThroughLegacyLobTextType() throws NoSuchFieldException {
        for (Guarded g : GUARDED) {
            Field field = g.entity().getDeclaredField(g.field());
            String where = g.entity().getSimpleName() + "." + g.field();

            Type type = field.getAnnotation(Type.class);
            assertThat(type).as("@Type on %s", where).isNotNull();
            assertThat(type.value()).as(where).isEqualTo(LegacyLobTextType.class);
            assertThat(type.parameters()).as(where)
                    .extracting(Parameter::name, Parameter::value)
                    .containsExactly(tuple(LegacyLobTextType.KEY_COLUMN, g.keyColumn()));

            Column column = field.getAnnotation(Column.class);
            assertThat(column).as("@Column on %s", where).isNotNull();
            assertThat(column.columnDefinition()).as(where).isEqualTo("TEXT");
            assertThat(column.nullable()).as(where).isEqualTo(!g.notNull());
            assertThat(column.name()).as(where).isEmpty();
            assertThat(column.unique()).as(where).isFalse();
            assertThat(column.insertable()).as(where).isTrue();
            assertThat(column.updatable()).as(where).isTrue();
            assertThat(field.getAnnotation(Lob.class)).as("no @Lob on %s", where).isNull();

            assertThat(g.entity().getAnnotation(DynamicUpdate.class)).as("@DynamicUpdate on %s", where).isNotNull();
            assertThat(Arrays.stream(g.entity().getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(PrePersist.class) && m.isAnnotationPresent(PreUpdate.class))
                    .map(Method::getName))
                    .as("@PrePersist @PreUpdate OID-shape callback on %s", g.entity().getSimpleName())
                    .hasSize(1);
        }
    }

    @Test
    void noQueryOutsideTheAllowlistNamesTheGuardedColumns() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = String.join("\n", codeLines(file));
            String fileName = file.getFileName().toString().replaceFirst("\\.java$", "");
            Matcher m = QUERY_ANNOTATION.matcher(code);
            while (m.find()) {
                int open = skipWhitespace(code, m.end());
                if (open >= code.length() || code.charAt(open) != '(') {
                    continue;
                }
                int close = matchingParen(code, open);
                String arguments = code.substring(open + 1, close);
                String id = fileName + "#" + methodNameAfter(code, close + 1);
                if (QUERY_ALLOWLIST.contains(id)) {
                    continue;
                }
                for (String name : COLUMN_NAMES) {
                    if (arguments.contains(name)) {
                        violations.add(MAIN_SOURCES.relativize(file) + " " + id + " names " + name);
                    }
                }
            }
        }
        assertThat(violations).as("@Query strings must not touch the legacy large-object TEXT columns").isEmpty();
    }

    @Test
    void mainCodeNeverUsesTheClientLargeObjectApiOrUnlinksObjects() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSources()) {
            boolean owner = MAIN_SOURCES.relativize(file).equals(LARGE_OBJECT_ACCESS_OWNER);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String fileName = file.getFileName().toString().replaceFirst("\\.java$", "");
            Set<Integer> allowedReads =
                    owner ? Set.<Integer>of() : allowedLargeObjectReadLines(fileName, lines);
            for (int i = 0; i < lines.size(); i++) {
                if (isCommentLine(lines.get(i))) {
                    continue;
                }
                for (String token : BANNED_TOKENS) {
                    if (lines.get(i).contains(token)) {
                        violations.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1) + " uses " + token);
                    }
                }
                Matcher access = LARGE_OBJECT_ACCESS.matcher(lines.get(i));
                while (!owner && access.find()) {
                    if (ALLOWLISTED_LARGE_OBJECT_READ.equals(access.group()) && allowedReads.contains(i + 1)) {
                        continue;
                    }
                    violations.add(MAIN_SOURCES.relativize(file) + ":" + (i + 1) + " uses " + access.group()
                            + " outside " + LARGE_OBJECT_ACCESS_OWNER.getFileName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theLargeObjectReadAllowlistExemptsOnlyTheNamedSearchQueries() {
        List<String> query = List.of(
                "    /** javadoc naming lo_get, with an apostrophe: the ticket's body. */",
                "    @Transactional(readOnly = true)",
                "    @Query(value = \"SELECT t.id FROM ticket t \"",
                "        + \"WHERE lower(convert_from(lo_get(t.description), 'UTF8')) LIKE :term\",",
                "        nativeQuery = true)",
                "    List<Long> findTicketIdsByDescriptionContaining(@Param(\"term\") String term);");

        assertThat(allowedLargeObjectReadLines("TicketRepository", query))
                .as("the allowlisted query's own @Query lines are exempt")
                .containsExactly(3, 4, 5);
        assertThat(allowedLargeObjectReadLines("SomeOtherRepository", query))
                .as("the same query in another file is not exempt")
                .isEmpty();
        assertThat(allowedLargeObjectReadLines("TicketRepository", List.of(
                "    @Query(value = \"SELECT lo_get(t.description) FROM ticket t\", nativeQuery = true)",
                "    List<byte[]> someNewLargeObjectRead();")))
                .as("a new method in an allowlisted file is not exempt")
                .isEmpty();
    }

    // ------------------------------------------------------------------ source scanning

    private static List<Path> mainSources() throws IOException {
        assertThat(MAIN_SOURCES).as("run from the module directory").isDirectory();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static boolean isCommentLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*");
    }

    private static List<String> codeLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !isCommentLine(l)).toList();
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Index of the parenthesis closing the one at {@code open}, skipping string and char literals. */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                for (i++; i < s.length() && s.charAt(i) != c; i++) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced parentheses after index " + open);
    }

    /**
     * The 1-based line numbers covered by the {@code @Query} arguments of this file's methods in
     * {@link #LARGE_OBJECT_READ_ALLOWLIST}. Comment lines are blanked rather than dropped so the
     * line numbers still line up with the file and an apostrophe or stray paren in javadoc cannot
     * derail {@link #matchingParen}.
     */
    private static Set<Integer> allowedLargeObjectReadLines(String fileName, List<String> lines) {
        Set<Integer> allowed = new LinkedHashSet<>();
        String code = String.join("\n", lines.stream().map(l -> isCommentLine(l) ? "" : l).toList());
        Matcher m = QUERY_ANNOTATION.matcher(code);
        while (m.find()) {
            int open = skipWhitespace(code, m.end());
            if (open >= code.length() || code.charAt(open) != '(') {
                continue;
            }
            int close = matchingParen(code, open);
            if (!LARGE_OBJECT_READ_ALLOWLIST.contains(fileName + "#" + methodNameAfter(code, close + 1))) {
                continue;
            }
            int last = lineOf(code, close);
            for (int line = lineOf(code, open); line <= last; line++) {
                allowed.add(line);
            }
        }
        return allowed;
    }

    /** The 1-based line of {@code index} in {@code code}. */
    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The name of the method an annotation at {@code from} belongs to, skipping further annotations. */
    private static String methodNameAfter(String s, int from) {
        int i = skipWhitespace(s, from);
        while (i < s.length() && s.charAt(i) == '@') {
            Matcher a = ANNOTATION_NAME.matcher(s);
            if (!a.find(i) || a.start() != i) {
                break;
            }
            i = skipWhitespace(s, a.end());
            if (i < s.length() && s.charAt(i) == '(') {
                i = skipWhitespace(s, matchingParen(s, i) + 1);
            }
        }
        int paren = s.indexOf('(', i);
        if (paren < 0) {
            return "?";
        }
        Matcher name = METHOD_NAME.matcher(s.substring(i, paren));
        return name.find() ? name.group(1) : "?";
    }
}
