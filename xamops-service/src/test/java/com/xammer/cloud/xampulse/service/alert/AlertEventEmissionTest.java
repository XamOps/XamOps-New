package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.domain.XpAlertState;
import com.xammer.cloud.xampulse.domain.XpIncident;
import com.xammer.cloud.xampulse.dto.XpAlertScope;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.dto.XpGrouping;
import com.xammer.cloud.xampulse.dto.XpRouting;
import com.xammer.cloud.xampulse.repository.XpAlertRuleRepository;
import com.xammer.cloud.xampulse.repository.XpAlertStateRepository;
import com.xammer.cloud.xampulse.service.insight.EventWriter;
import com.xammer.cloud.xampulse.service.platform.ClickHouseWrites;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The alert evaluator mirrors firing/anomaly/resolution transitions into the xp_events timeline through the optional
 * EventWriter, and a write failure never breaks evaluation.
 */
class AlertEventEmissionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-02-02T12:00:00Z");

    /** Captures xp_events rows written during evaluation. */
    private static final class CapturingWrites implements ClickHouseWrites {
        final List<List<Object>> events = new ArrayList<>();
        SQLException toThrow;

        @Override
        public void insert(String table, List<String> columns, List<List<Object>> rows) throws SQLException {
            if (toThrow != null) {
                throw toThrow;
            }
            if ("xp_events".equals(table)) {
                events.addAll(rows);
            }
        }
    }

    private com.xammer.cloud.xampulse.domain.XpAlertRule entity(String type) throws Exception {
        XpCondition cond = "METRIC_ANOMALY".equals(type)
                ? new XpCondition("METRIC_ANOMALY", "system.cpu.load_average.1m", null, null, null, "15m", null, null, "0m", "KEEP", null)
                : new XpCondition("METRIC_THRESHOLD", "system.cpu.utilization", null, null, "avg", "5m", ">", 0.9, "0m", "KEEP", null);
        com.xammer.cloud.xampulse.dto.XpAlertRule dto = new com.xammer.cloud.xampulse.dto.XpAlertRule(
                1L, 0L, "High CPU", true, "HIGH", new XpAlertScope(List.of(7L), List.of("i-1"), null), cond,
                new XpGrouping(List.of("instance_id"), "15m"), new XpRouting(List.of(), false, null));
        com.xammer.cloud.xampulse.domain.XpAlertRule e = new com.xammer.cloud.xampulse.domain.XpAlertRule();
        e.setId(1L);
        e.setCloudAccountId(7L);
        e.setName("High CPU");
        e.setSeverity("HIGH");
        e.setEnabled(true);
        e.setDefinitionJson(mapper.writeValueAsString(dto));
        return e;
    }

    private AlertEvaluator evaluator(CapturingWrites writes, StubCollaborators c) {
        AlertEvaluator ev = new AlertEvaluator(c.rules, c.states, c.conditions, c.incidents, c.correlator,
                c.silences, c.router, schedulingOn(), mapper);
        ev.setEventWriter(new EventWriter(writes));
        return ev;
    }

    private SchedulingEnvironmentConfig schedulingOn() {
        SchedulingEnvironmentConfig s = mock(SchedulingEnvironmentConfig.class);
        when(s.isSchedulingEnabled()).thenReturn(true);
        return s;
    }

    @Test
    void firingThresholdRuleRecordsAnAlertEvent() throws Exception {
        CapturingWrites writes = new CapturingWrites();
        StubCollaborators c = new StubCollaborators();
        when(c.conditions.observe(anyLong(), any(), any()))
                .thenReturn(List.of(new ConditionEvaluator.Observation("i-1", 0.95, true, null)));

        evaluator(writes, c).evaluateRule(entity("METRIC_THRESHOLD"), now);

        assertEquals(1, writes.events.size());
        assertEquals("ALERT", writes.events.get(0).get(2));
        assertEquals("i-1", writes.events.get(0).get(3));
    }

    @Test
    void firingAnomalyRuleRecordsAnAnomalyEvent() throws Exception {
        CapturingWrites writes = new CapturingWrites();
        StubCollaborators c = new StubCollaborators();
        when(c.conditions.observe(anyLong(), any(), any()))
                .thenReturn(List.of(new ConditionEvaluator.Observation("i-1", 7.0, true, null)));

        evaluator(writes, c).evaluateRule(entity("METRIC_ANOMALY"), now);

        assertEquals(1, writes.events.size());
        assertEquals("ANOMALY", writes.events.get(0).get(2));
    }

    @Test
    void resolutionRecordsARecoveredEvent() throws Exception {
        CapturingWrites writes = new CapturingWrites();
        StubCollaborators c = new StubCollaborators();
        XpAlertState firing = new XpAlertState();
        firing.setRuleId(1L);
        firing.setGroupKey("i-1");
        firing.setState("FIRING");
        firing.setSince(now.minusSeconds(3600));
        when(c.states.findByRuleId(1L)).thenReturn(List.of(firing));
        when(c.conditions.observe(anyLong(), any(), any()))
                .thenReturn(List.of(new ConditionEvaluator.Observation("i-1", 0.1, false, null)));

        evaluator(writes, c).evaluateRule(entity("METRIC_THRESHOLD"), now);

        assertEquals(1, writes.events.size());
        assertEquals("ALERT", writes.events.get(0).get(2));
        assertTrue(((String) writes.events.get(0).get(4)).contains("recovered"));
    }

    @Test
    void eventWriterFailureDoesNotFailEvaluation() throws Exception {
        CapturingWrites writes = new CapturingWrites();
        writes.toThrow = new SQLException("ch down");
        StubCollaborators c = new StubCollaborators();
        when(c.conditions.observe(anyLong(), any(), any()))
                .thenReturn(List.of(new ConditionEvaluator.Observation("i-1", 0.95, true, null)));

        com.xammer.cloud.xampulse.domain.XpAlertRule e = entity("METRIC_THRESHOLD");
        AlertEvaluator ev = evaluator(writes, c);
        assertDoesNotThrow(() -> ev.evaluateRule(e, now));
        verify(c.incidents).openOrJoin(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
    }

    /** Bundle of mocked collaborators with sensible defaults. */
    private static final class StubCollaborators {
        final XpAlertRuleRepository rules = mock(XpAlertRuleRepository.class);
        final XpAlertStateRepository states = mock(XpAlertStateRepository.class);
        final ConditionEvaluator conditions = mock(ConditionEvaluator.class);
        final IncidentService incidents = mock(IncidentService.class);
        final IncidentCorrelator correlator = mock(IncidentCorrelator.class);
        final SilenceService silences = mock(SilenceService.class);
        final NotificationRouter router = mock(NotificationRouter.class);

        StubCollaborators() {
            XpIncident inc = new XpIncident();
            inc.setId(42L);
            when(incidents.openOrJoin(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any()))
                    .thenReturn(inc);
            when(states.findByRuleId(anyLong())).thenReturn(List.of());
            when(states.save(any())).thenAnswer(i -> i.getArgument(0));
        }
    }
}
