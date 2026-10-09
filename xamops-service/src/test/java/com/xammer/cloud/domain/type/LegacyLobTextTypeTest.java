package com.xammer.cloud.domain.type;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.spi.TypeBootstrapContext;
import org.hibernate.usertype.DynamicParameterizedType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no database) for the shared candidate rule, {@link LobTextWriteMode},
 * {@link OidShapeGuard} and {@link LegacyLobTextType} driven through JDBC mocks.
 * The PostgreSQL behaviour itself is covered by the {@code *PgTest} classes.
 */
class LegacyLobTextTypeTest {

    private static final String JSON = "{\"x\":\"y\"}";

    private final List<Logger> capturedLoggers = new ArrayList<>();
    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    @AfterEach
    void detachAppenders() {
        for (int i = 0; i < capturedLoggers.size(); i++) {
            capturedLoggers.get(i).detachAppender(appenders.get(i));
        }
    }

    private ListAppender<ILoggingEvent> capture(Class<?> source) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        capturedLoggers.add(logger);
        appenders.add(appender);
        return appender;
    }

    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    // ------------------------------------------------------------------ candidate rule

    static Stream<Arguments> vectors() {
        return PgTestSupport.candidateVectors().stream()
                .map(v -> Arguments.of("'" + v.value() + "'", v.value(), v.candidate()));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("vectors")
    void candidateRuleMatchesTheSharedVectors(String label, String value, boolean expected) {
        assertThat(LegacyLobTextType.isCandidate(value)).isEqualTo(expected);
    }

    @Test
    void sharedVectorsCoverTheRequiredExamplesAndBoundaries() {
        Map<String, Boolean> byValue = new HashMap<>();
        PgTestSupport.candidateVectors().forEach(v -> byValue.put(v.value(), v.candidate()));

        assertThat(byValue).containsKeys("0", "0.0", "42", "16383", "016842", "4294967296", "9999999999",
                "[]", JSON, "null", "", "1953.07", "1.0E10", "-16842", "+16842", " 16842");
        assertThat(byValue).containsEntry("16384", true).containsEntry("4294967295", true)
                .containsEntry("16383", false).containsEntry("4294967296", false);
    }

    @Test
    void nullIsNotACandidate() {
        assertThat(LegacyLobTextType.isCandidate(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\u0661\u0666\u0668\u0664\u0662",   // Arabic-Indic digits: Long.parseLong would accept them
            "\uFF11\uFF16\uFF18\uFF14\uFF12",   // fullwidth digits
            "16842\n", "\n16842", "16842\t", "16842\r\n"})
    void nonAsciiDigitsAndSurroundingControlCharactersAreNotCandidates(String value) {
        assertThat(LegacyLobTextType.isCandidate(value)).isFalse();
    }

    // ------------------------------------------------------------------ LobTextWriteMode

    @Test
    void writeModeDefaultsToLegacy() {
        assertThat(LobTextWriteMode.parse(null)).isEqualTo(LobTextWriteMode.LEGACY);
        assertThat(LobTextWriteMode.parse("")).isEqualTo(LobTextWriteMode.LEGACY);
        assertThat(LobTextWriteMode.parse("   ")).isEqualTo(LobTextWriteMode.LEGACY);
    }

    @Test
    void writeModeIsTrimmedAndCaseInsensitive() {
        assertThat(LobTextWriteMode.parse(" Inline ")).isEqualTo(LobTextWriteMode.INLINE);
        assertThat(LobTextWriteMode.parse("inline")).isEqualTo(LobTextWriteMode.INLINE);
        assertThat(LobTextWriteMode.parse("LEGACY")).isEqualTo(LobTextWriteMode.LEGACY);
        assertThat(LobTextWriteMode.parse("legacy")).isEqualTo(LobTextWriteMode.LEGACY);
    }

    @Test
    void invalidWriteModeFailsWithThePropertyNameAndAllowedValues() {
        assertThatThrownBy(() -> LobTextWriteMode.parse("lob"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("xammer.lob-text.write-mode must be 'legacy' or 'inline', got 'lob'");
    }

    @Test
    void invalidWriteModeFailsTypeBootstrap() {
        assertThatThrownBy(() -> new LegacyLobTextType(bootstrap("plain")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LobTextWriteMode.PROPERTY);
    }

    @Test
    void effectiveModeReadsTheHibernateSetting() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        when(emf.getProperties()).thenReturn(Map.of(LobTextWriteMode.PROPERTY, "inline"));
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.INLINE);

        when(emf.getProperties()).thenReturn(Map.of());
        assertThat(LobTextWriteMode.effective(emf)).isEqualTo(LobTextWriteMode.LEGACY);
    }

    // ------------------------------------------------------------------ OidShapeGuard

    @Test
    void entityWriteGuardWarnsWithTableAndKeyButNeverTheValue() {
        ListAppender<ILoggingEvent> log = capture(OidShapeGuard.class);

        assertThatThrownBy(() -> OidShapeGuard.checkEntityWrite("cached_data", "GCP_MONTHLY_TOTAL::p::2026-09", "16842"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("16842");

        assertThat(warnings(log)).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage())
                    .isEqualTo("Refused OID-shaped value table=cached_data key=GCP_MONTHLY_TOTAL::p::2026-09");
            assertThat(e.getFormattedMessage()).doesNotContain("16842");
        });
    }

    @Test
    void entityWriteGuardNamesAnUnassignedIdentityKeyNew() {
        ListAppender<ILoggingEvent> log = capture(OidShapeGuard.class);

        assertThatThrownBy(() -> OidShapeGuard.checkEntityWrite("cloudsitter_policies", null, "4294967295"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(warnings(log)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Refused OID-shaped value table=cloudsitter_policies key=new");
    }

    @Test
    void entityWriteGuardAcceptsEveryNonCandidate() {
        ListAppender<ILoggingEvent> log = capture(OidShapeGuard.class);

        for (String value : new String[] {null, "", JSON, "0", "16383", "016842", "4294967296", "1953.07"}) {
            assertThatCode(() -> OidShapeGuard.checkEntityWrite("cached_data", "k", value)).doesNotThrowAnyException();
        }
        assertThat(log.list).isEmpty();
    }

    @Test
    void apiGuardRejectsCandidatesWithoutEchoingOrLoggingThem() {
        ListAppender<ILoggingEvent> log = capture(OidShapeGuard.class);

        assertThatThrownBy(() -> OidShapeGuard.requireNotOidShaped("scheduleConfig", "16842"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scheduleConfig must not be a bare number between 16384 and 4294967295");
        assertThatCode(() -> OidShapeGuard.requireNotOidShaped("scheduleConfig", null)).doesNotThrowAnyException();
        assertThatCode(() -> OidShapeGuard.requireNotOidShaped("scheduleConfig", JSON)).doesNotThrowAnyException();
        assertThat(log.list).isEmpty();
    }

    // ------------------------------------------------------------------ LegacyLobTextType

    private static TypeBootstrapContext bootstrap(String mode) {
        TypeBootstrapContext ctx = mock(TypeBootstrapContext.class);
        Map<String, Object> settings = new HashMap<>();
        if (mode != null) {
            settings.put(LobTextWriteMode.PROPERTY, mode);
        }
        when(ctx.getConfigurationSettings()).thenReturn(settings);
        return ctx;
    }

    /** A type bound to cached_data.json_data, as Hibernate would configure it. */
    private static LegacyLobTextType cachedDataType(String mode) {
        LegacyLobTextType type = new LegacyLobTextType(bootstrap(mode));
        DynamicParameterizedType.ParameterType pt = mock(DynamicParameterizedType.ParameterType.class);
        when(pt.getTable()).thenReturn("cached_data");
        when(pt.getColumns()).thenReturn(new String[] {"json_data"});
        Properties parameters = new Properties();
        parameters.put(DynamicParameterizedType.PARAMETER_TYPE, pt);
        parameters.setProperty(LegacyLobTextType.KEY_COLUMN, "cache_key");
        type.setParameterValues(parameters);
        return type;
    }

    private static WrapperOptions options(Dialect dialect) {
        WrapperOptions options = mock(WrapperOptions.class);
        when(options.getDialect()).thenReturn(dialect);
        return options;
    }

    private static WrapperOptions postgres() {
        return options(mock(PostgreSQLDialect.class));
    }

    private static WrapperOptions otherDialect() {
        return options(mock(Dialect.class));
    }

    @Test
    void typeReadsTheModeFromTheBootstrapSettings() {
        assertThat(new LegacyLobTextType(bootstrap(null)).writeMode()).isEqualTo(LobTextWriteMode.LEGACY);
        assertThat(new LegacyLobTextType(bootstrap(" INLINE ")).writeMode()).isEqualTo(LobTextWriteMode.INLINE);
    }

    @Test
    void typeKeepsTheJdbcContractOfAPlainString() {
        LegacyLobTextType type = cachedDataType(null);
        assertThat(type.getSqlType()).isEqualTo(Types.VARCHAR);
        assertThat(type.returnedClass()).isEqualTo(String.class);
        assertThat(type.isMutable()).isFalse();
        assertThat(type.deepCopy(JSON)).isSameAs(JSON);
        assertThat(type.equals(JSON, new String(JSON))).isTrue();
        assertThat(type.equals("16842", "16843")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cached-data", "cached_data; DROP TABLE x", "Cached_Data", ""})
    void unsafeIdentifiersFailBootstrap(String table) {
        LegacyLobTextType type = new LegacyLobTextType(bootstrap(null));
        DynamicParameterizedType.ParameterType pt = mock(DynamicParameterizedType.ParameterType.class);
        when(pt.getTable()).thenReturn(table);
        when(pt.getColumns()).thenReturn(new String[] {"json_data"});
        Properties parameters = new Properties();
        parameters.put(DynamicParameterizedType.PARAMETER_TYPE, pt);
        parameters.setProperty(LegacyLobTextType.KEY_COLUMN, "cache_key");

        assertThatThrownBy(() -> type.setParameterValues(parameters)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherDialectsPassValuesThroughUnchanged() throws SQLException {
        for (String mode : new String[] {"legacy", "inline"}) {
            LegacyLobTextType type = cachedDataType(mode);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(3)).thenReturn("16842");
            assertThat(type.nullSafeGet(rs, 3, otherDialect())).isEqualTo("16842");
            verify(rs, never()).getStatement();

            PreparedStatement st = mock(PreparedStatement.class);
            type.nullSafeSet(st, "16842", 2, otherDialect());
            type.nullSafeSet(st, JSON, 4, otherDialect());
            verify(st).setString(2, "16842");
            verify(st).setString(4, JSON);
            verify(st, never()).getConnection();
        }
    }

    @Test
    void nullBindsAsVarcharNullInEveryModeAndDialect() throws SQLException {
        for (String mode : new String[] {"legacy", "inline"}) {
            for (WrapperOptions options : new WrapperOptions[] {postgres(), otherDialect()}) {
                PreparedStatement st = mock(PreparedStatement.class);
                cachedDataType(mode).nullSafeSet(st, null, 5, options);
                verify(st).setNull(5, Types.VARCHAR);
                verify(st, never()).getConnection();
            }
        }
    }

    @Test
    void legacyWriteRefusesAnAutoCommitConnectionBeforeAnySql() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        PreparedStatement st = mock(PreparedStatement.class);
        when(st.getConnection()).thenReturn(connection);

        assertThatThrownBy(() -> cachedDataType(null).nullSafeSet(st, JSON, 1, postgres()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("legacy LO write to cached_data.json_data needs a read-write transaction");
        verify(connection, never()).prepareStatement(anyString());
        verify(st, never()).setString(anyInt(), any());
    }

    @Test
    void legacyWriteRefusesAReadOnlyConnectionBeforeAnySql() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        PreparedStatement st = mock(PreparedStatement.class);
        when(st.getConnection()).thenReturn(connection);

        assertThatThrownBy(() -> cachedDataType("legacy").nullSafeSet(st, JSON, 1, postgres()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a read-write transaction");
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void legacyWriteStoresTheNewObjectsOidAsDecimalText() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement create = mock(PreparedStatement.class);
        ResultSet created = mock(ResultSet.class);
        when(connection.prepareStatement(LegacyLobTextType.CREATE_SQL)).thenReturn(create);
        when(create.executeQuery()).thenReturn(created);
        when(created.next()).thenReturn(true);
        when(created.getLong(1)).thenReturn(4_294_967_295L);
        PreparedStatement st = mock(PreparedStatement.class);
        when(st.getConnection()).thenReturn(connection);

        cachedDataType(null).nullSafeSet(st, PgTestSupport.NON_ASCII_CONTENT, 7, postgres());

        assertThat(LegacyLobTextType.CREATE_SQL).isEqualTo("SELECT lo_from_bytea(0, ?)");
        verify(create).setBytes(1, PgTestSupport.NON_ASCII_CONTENT.getBytes(StandardCharsets.UTF_8));
        verify(st).setString(7, "4294967295");
    }

    @Test
    void inlineWriteRefusesACandidateWithAWarnNamingTableAndColumn() throws SQLException {
        ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
        PreparedStatement st = mock(PreparedStatement.class);

        assertThatThrownBy(() -> cachedDataType("inline").nullSafeSet(st, "16842", 1, postgres()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("16842");

        verify(st, never()).setString(anyInt(), any());
        verify(st, never()).getConnection();
        assertThat(warnings(log)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Refused OID-shaped value table=cached_data column=json_data");
    }

    @Test
    void inlineWriteStoresOtherValuesAsPlainText() throws SQLException {
        PreparedStatement st = mock(PreparedStatement.class);
        LegacyLobTextType type = cachedDataType("inline");
        type.nullSafeSet(st, JSON, 1, postgres());
        type.nullSafeSet(st, "16383", 2, postgres());
        verify(st).setString(1, JSON);
        verify(st).setString(2, "16383");
        verify(st, never()).getConnection();
    }

    @Test
    void readReturnsNonCandidatesWithoutAnExtraStatement() throws SQLException {
        LegacyLobTextType type = cachedDataType(null);
        for (String raw : new String[] {JSON, "0.0", "16383", "016842", "4294967296", ""}) {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn(raw);
            assertThat(type.nullSafeGet(rs, 1, postgres())).isEqualTo(raw);
            verify(rs, never()).getStatement();
        }
        ResultSet nullColumn = mock(ResultSet.class);
        assertThat(type.nullSafeGet(nullColumn, 1, postgres())).isNull();
        verify(nullColumn, never()).getStatement();
    }

    /** Mocks for one candidate read: the column, its connection, and the resolve and key statements. */
    private static final class ReadFixture {
        final Connection connection = mock(Connection.class);
        final ResultSet column = mock(ResultSet.class);
        final PreparedStatement keyLookup = mock(PreparedStatement.class);

        ReadFixture(String raw, boolean exists, boolean canRead, byte[] bytes) throws SQLException {
            Statement statement = mock(Statement.class);
            when(column.getString(1)).thenReturn(raw);
            when(column.getStatement()).thenReturn(statement);
            when(statement.getConnection()).thenReturn(connection);

            PreparedStatement resolve = mock(PreparedStatement.class);
            ResultSet resolved = mock(ResultSet.class);
            when(connection.prepareStatement(LegacyLobTextType.RESOLVE_SQL)).thenReturn(resolve);
            when(resolve.executeQuery()).thenReturn(resolved);
            when(resolved.next()).thenReturn(true);
            when(resolved.getBoolean(1)).thenReturn(exists);
            when(resolved.getBoolean(2)).thenReturn(canRead);
            when(resolved.getBytes(3)).thenReturn(bytes);

            ResultSet key = mock(ResultSet.class);
            when(connection.prepareStatement(startsWith("SELECT cache_key::text FROM cached_data WHERE json_data = ?")))
                    .thenReturn(keyLookup);
            when(keyLookup.executeQuery()).thenReturn(key);
            when(key.next()).thenReturn(true);
            when(key.getString(1)).thenReturn("GCP_MONTHLY_TOTAL::p::2026-09");
        }
    }

    @Test
    void readResolvesACandidateOnTheSameConnection() throws SQLException {
        ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
        ReadFixture f = new ReadFixture("16842", true, true, PgTestSupport.NON_ASCII_CONTENT.getBytes(StandardCharsets.UTF_8));

        assertThat(cachedDataType(null).nullSafeGet(f.column, 1, postgres())).isEqualTo(PgTestSupport.NON_ASCII_CONTENT);
        assertThat(warnings(log)).isEmpty();
        verify(f.connection, never()).prepareStatement(startsWith("SELECT cache_key"));
    }

    @Test
    void missingObjectReadsAsNullWithOneRateLimitedWarn() throws SQLException {
        ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
        LegacyLobTextType type = cachedDataType(null);
        ReadFixture f = new ReadFixture("16843", false, false, null);

        assertThat(type.nullSafeGet(f.column, 1, postgres())).isNull();
        assertThat(type.nullSafeGet(f.column, 1, postgres())).isNull();   // same OID again: DEBUG only

        assertThat(warnings(log)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Unreadable legacy large-object reference table=cached_data column=json_data"
                        + " key=GCP_MONTHLY_TOTAL::p::2026-09 oid=16843 reason=missing");
        verify(f.keyLookup).setString(1, "16843");
    }

    @Test
    void unreadableObjectReadsAsNullWithReasonPermission() throws SQLException {
        ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
        ReadFixture f = new ReadFixture("16844", true, false, null);

        assertThat(cachedDataType(null).nullSafeGet(f.column, 1, postgres())).isNull();
        assertThat(warnings(log)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().endsWith("oid=16844 reason=permission");
    }

    @Test
    void undecodableContentReadsAsNullWithReasonInvalidUtf8AndNoPayload() throws SQLException {
        ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
        LegacyLobTextType type = cachedDataType(null);

        assertThat(type.nullSafeGet(new ReadFixture("16845", true, true, PgTestSupport.INVALID_UTF8).column, 1, postgres()))
                .isNull();
        assertThat(type.nullSafeGet(new ReadFixture("16846", true, true, PgTestSupport.NUL_BYTES).column, 1, postgres()))
                .isNull();

        assertThat(warnings(log)).hasSize(2).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .endsWith("reason=invalid-utf8")
                .doesNotContain("a\u0000b"));
    }

    @Test
    void strictDecodeAcceptsOnlyWellFormedUtf8WithoutNul() {
        assertThat(LegacyLobTextType.decodeStrict(PgTestSupport.NON_ASCII_CONTENT.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PgTestSupport.NON_ASCII_CONTENT);
        assertThat(LegacyLobTextType.decodeStrict(new byte[0])).isEmpty();
        assertThat(LegacyLobTextType.decodeStrict(PgTestSupport.INVALID_UTF8)).isNull();
        assertThat(LegacyLobTextType.decodeStrict(PgTestSupport.NUL_BYTES)).isNull();
        assertThat(LegacyLobTextType.decodeStrict(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80})).isNull(); // surrogate
        assertThat(LegacyLobTextType.decodeStrict(new byte[] {(byte) 0xC0, (byte) 0xAF})).isNull();              // overlong
        assertThat(LegacyLobTextType.decodeStrict(null)).isNull();
    }

    @Nested
    class WithoutMappingParameters {

        @Test
        void labelsFallBackToQuestionMarksAndNoKeyLookupRuns() throws SQLException {
            ListAppender<ILoggingEvent> log = capture(LegacyLobTextType.class);
            LegacyLobTextType type = new LegacyLobTextType(bootstrap(null));
            type.setParameterValues(new Properties());
            ReadFixture f = new ReadFixture("16847", false, false, null);

            assertThat(type.nullSafeGet(f.column, 1, postgres())).isNull();
            assertThat(warnings(log)).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                    .isEqualTo("Unreadable legacy large-object reference table=? column=? key=? oid=16847 reason=missing");
            verify(f.connection, never()).prepareStatement(startsWith("SELECT cache_key"));
        }
    }
}
