package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 slice test for the automation queries on {@code agent_events} (design §11.3): heartbeat rows
 * must not push a configuration change out of the Activity feed, and the prefix matches literally.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class AgentEventRepositoryAutomationQueriesTest {

    @Autowired
    AgentEventRepository repository;

    private AgentEvent event(long configId, String type, LocalDateTime at, String details) {
        AgentEvent e = new AgentEvent(configId, type);
        e.setCreatedAt(at);
        e.setDetails(details);
        return repository.save(e);
    }

    @Test
    void recentChangesSurviveFiftyNewerHeartbeats() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 10, 8, 0);
        event(10L, "autoscaling_config_change", base, "Evictor policy updated by jane.doe");
        for (int i = 1; i <= 50; i++) {
            event(10L, "heartbeat", base.plusMinutes(i), null);
        }
        event(11L, "autoscaling_config_change", base.plusHours(1), "another cluster");

        List<AgentEvent> changes = repository
                .findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(10L, "autoscaling_");

        assertThat(changes).extracting(AgentEvent::getDetails).containsExactly("Evictor policy updated by jane.doe");
    }

    @Test
    void newestTwentyFirst_andTheUnderscoreIsLiteral() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 10, 8, 0);
        for (int i = 0; i < 25; i++) {
            event(10L, "autoscaling_config_change", base.plusMinutes(i), "change " + i);
        }
        // "_" would match any character in an unescaped LIKE.
        event(10L, "autoscalingXconfig", base.plusHours(2), "not a change");

        List<AgentEvent> changes = repository
                .findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(10L, "autoscaling_");

        assertThat(changes).hasSize(20);
        assertThat(changes.get(0).getDetails()).isEqualTo("change 24");
        assertThat(changes).extracting(AgentEvent::getDetails).doesNotContain("not a change");
    }
}
