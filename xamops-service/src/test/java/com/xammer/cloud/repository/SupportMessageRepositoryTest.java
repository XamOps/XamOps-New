package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import javax.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class SupportMessageRepositoryTest {

    @Autowired
    private SupportMessageRepository repo;

    @Autowired
    private EntityManager entityManager;

    @Test
    void save_persistsContentLongerThan255Characters() {
        Client client = new Client();
        client.setName("test-client");
        entityManager.persist(client);

        User user = new User();
        user.setUsername("test-user");
        user.setPassword("x");
        user.setRole("XAMOPS");
        user.setClient(client);
        entityManager.persist(user);

        // Regression: a chatbot FAQ auto-answer (full sentence description +
        // follow-up nudge) routinely exceeds 255 characters. This used to fail
        // with "value too long for type character varying(255)" on Postgres
        // because SupportMessage.content had no columnDefinition override.
        String longContent = "XamSecure is a cloud security (CNAPP) module covering an Overview, Issues, " +
                "asset Inventory, Attack Surface (internet-exposed resources), Vulnerabilities (CVE " +
                "tracking), Posture, API Security, Threats, Compliance, and account Onboarding. " +
                "Did this answer your question? If not, just send another message and I'll loop in our support team.";
        assertThat(longContent.length()).isGreaterThan(255);

        SupportMessage message = new SupportMessage(user, longContent, false);
        SupportMessage saved = repo.saveAndFlush(message);

        assertThat(saved.getId()).isNotNull();
        assertThat(repo.findById(saved.getId()).orElseThrow().getContent()).isEqualTo(longContent);
    }
}
