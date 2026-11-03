package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.insight.Ec2StateEventProjection.Ec2Instance;
import com.xammer.cloud.xampulse.service.insight.Ec2StateEventProjection.Ec2StateSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2StateEventProjectionTest {

    private final Instant now = Instant.parse("2026-02-02T12:00:00Z");

    private SchedulingEnvironmentConfig schedulingOn() {
        SchedulingEnvironmentConfig s = mock(SchedulingEnvironmentConfig.class);
        when(s.isSchedulingEnabled()).thenReturn(true);
        return s;
    }

    @Test
    void recordsEachStateOncePerHourThenDedupes() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        EventWriter writer = new EventWriter(writes);
        Ec2StateSource source = tenant -> List.of(new Ec2Instance("i-1", "running"), new Ec2Instance("i-2", "stopped"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, writer, schedulingOn());

        p.runForAccount(7L, now);
        p.runForAccount(7L, now.plusSeconds(600)); // same hour -> deduped

        assertEquals(2, writes.calls.size(), "two instances, each written once in the hour");
        assertEquals("EC2_STATE", writes.calls.get(0).rows().get(0).get(2));
    }

    @Test
    void aStateChangeWithinTheHourIsANewEvent() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        EventWriter writer = new EventWriter(writes);
        boolean[] stopped = {false};
        Ec2StateSource source = tenant -> List.of(new Ec2Instance("i-1", stopped[0] ? "stopped" : "running"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, writer, schedulingOn());

        p.runForAccount(7L, now);
        stopped[0] = true;
        p.runForAccount(7L, now.plusSeconds(60));

        assertEquals(2, writes.calls.size(), "running then stopped are distinct (resource,state) keys");
    }

    @Test
    void writeErrorsAreSwallowed() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        writes.toThrow = new SQLException("ch down");
        EventWriter writer = new EventWriter(writes);
        Ec2StateSource source = tenant -> List.of(new Ec2Instance("i-1", "running"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, writer, schedulingOn());

        assertDoesNotThrow(() -> p.runForAccount(7L, now));
    }

    @Test
    void oneAccountFailingDoesNotStopTheOthers() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        EventWriter writer = new EventWriter(writes);
        Ec2StateSource source = tenant -> {
            if (tenant == 7L) {
                throw new RuntimeException("source blew up for 7");
            }
            return List.of(new Ec2Instance("i-9", "running"));
        };
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED"), account(9L, "CONNECTED")));
        Ec2StateEventProjection p = new Ec2StateEventProjection(configs, source, writer, schedulingOn());

        p.runAll();

        assertEquals(1, writes.calls.size());
        assertEquals("i-9", writes.calls.get(0).rows().get(0).get(3));
    }

    @Test
    void schedulingDisabledDoesNothing() {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        SchedulingEnvironmentConfig off = mock(SchedulingEnvironmentConfig.class);
        when(off.isSchedulingEnabled()).thenReturn(false);
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        Ec2StateSource source = tenant -> List.of(new Ec2Instance("i-1", "running"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(configs, source, new EventWriter(writes), off);

        p.runAll();

        assertTrue(writes.calls.isEmpty());
    }

    private XpAccountConfig account(long id, String state) {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(id);
        c.setState(state);
        return c;
    }
}
