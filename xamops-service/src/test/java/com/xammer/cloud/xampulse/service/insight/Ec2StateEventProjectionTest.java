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
    void theFirstPassIsTheBaselineAndRepeatsOfTheSameStateAreNotChanges() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        EventWriter writer = new EventWriter(writes);
        Ec2StateSource source = tenant -> List.of(new Ec2Instance("i-0aaaaaaaa1", "running"), new Ec2Instance("i-0aaaaaaaa2", "stopped"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, writer, schedulingOn());

        p.runForAccount(7L, now);
        p.runForAccount(7L, now.plusSeconds(3600));
        p.runForAccount(7L, now.plusSeconds(7200));

        assertTrue(writes.calls.isEmpty(), "nothing changed: an instance that stays running is not an event every hour");
    }

    @Test
    void aStateChangeOrANewInstanceIsAnEventOnce() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        EventWriter writer = new EventWriter(writes);
        boolean[] later = {false};
        Ec2StateSource source = tenant -> later[0]
                ? List.of(new Ec2Instance("i-0aaaaaaaa1", "stopped"), new Ec2Instance("i-0bbbbbbbb2", "running"))
                : List.of(new Ec2Instance("i-0aaaaaaaa1", "running"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, writer, schedulingOn());

        p.runForAccount(7L, now);
        later[0] = true;
        p.runForAccount(7L, now.plusSeconds(60));
        p.runForAccount(7L, now.plusSeconds(120));

        assertEquals(2, writes.calls.size(), "running -> stopped, and a new instance; each once");
        assertEquals("EC2 i-0aaaaaaaa1 is stopped", writes.calls.get(0).rows().get(0).get(4));
        assertEquals("was running", writes.calls.get(0).rows().get(0).get(5));
        assertEquals("new instance", writes.calls.get(1).rows().get(0).get(5));
    }

    @Test
    void keyPairsVolumesAndOtherEc2ResourcesAreNotInstances() throws Exception {
        FakeClickHouseWrites writes = new FakeClickHouseWrites();
        boolean[] later = {false};
        Ec2StateSource source = tenant -> later[0]
                ? List.of(new Ec2Instance("key-0fde76a1a1243620c", "Available"), new Ec2Instance("vol-0abc12345", "in-use"))
                : List.of(new Ec2Instance("i-0aaaaaaaa1", "running"));
        Ec2StateEventProjection p = new Ec2StateEventProjection(
                mock(XpAccountConfigRepository.class), source, new EventWriter(writes), schedulingOn());

        p.runForAccount(7L, now);
        later[0] = true;
        p.runForAccount(7L, now.plusSeconds(60));

        assertTrue(writes.calls.isEmpty(), "only i-... ids are instances; an empty instance read keeps the baseline");
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
        String[] state9 = {"running"};
        Ec2StateSource source = tenant -> {
            if (tenant == 7L) {
                throw new RuntimeException("source blew up for 7");
            }
            return List.of(new Ec2Instance("i-0999999999", state9[0]));
        };
        XpAccountConfigRepository configs = mock(XpAccountConfigRepository.class);
        when(configs.findAll()).thenReturn(List.of(account(7L, "CONNECTED"), account(9L, "CONNECTED")));
        Ec2StateEventProjection p = new Ec2StateEventProjection(configs, source, writer, schedulingOn());

        p.runAll(); // baseline for 9
        p.runForAccount(9L, now); // still the same: nothing
        state9[0] = "stopped";
        p.runAll();

        assertEquals(1, writes.calls.size(), "account 7 failing every time does not stop account 9's change");
        assertEquals("i-0999999999", writes.calls.get(0).rows().get(0).get(3));
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
