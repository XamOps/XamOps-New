package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 slice test for the derived query spot-interruption tracking reads its
 * {@code xamops-node-drained} rows with: one cluster, one type, created after the cutoff.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class AgentEventRepositoryDrainCaptureTest {

    @Autowired
    AgentEventRepository repository;

    private void event(long configId, String type, LocalDateTime at, String details) {
        AgentEvent e = new AgentEvent(configId, type);
        e.setCreatedAt(at);
        e.setDetails(details);
        repository.save(e);
    }

    @Test
    void returnsOneClustersEventsOfOneTypeAfterTheCutoff() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 24, 15, 30);
        event(10L, "xamops-node-drained", now.minusMinutes(10), "recent");
        event(10L, "xamops-node-drained", now.minusHours(3), "too old");
        event(10L, "push_success", now.minusMinutes(5), "other type");
        event(11L, "xamops-node-drained", now.minusMinutes(5), "other cluster");

        assertThat(repository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(10L, "xamops-node-drained",
                now.minusHours(2))).extracting(AgentEvent::getDetails).containsExactly("recent");
        assertThat(repository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(12L, "xamops-node-drained",
                now.minusHours(2))).isEmpty();
    }
}
