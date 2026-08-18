package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;

import javax.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void findBySenderOrderByTimestampDesc_returnsOnlyTheMostRecentPage() {
        User user = persistUser("paged-user");

        // 10 messages, oldest ("msg-0") to newest ("msg-9").
        for (int i = 0; i < 10; i++) {
            SupportMessage m = new SupportMessage(user, "msg-" + i, true);
            m.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i));
            repo.save(m);
        }
        repo.flush();

        // The chat history endpoint asks for the newest N rather than the whole
        // conversation, so the pool isn't hammered by a 5s poll over unbounded history.
        List<SupportMessage> newestThree =
                repo.findBySenderOrderByTimestampDesc(user, PageRequest.of(0, 3));

        assertThat(newestThree).extracting(SupportMessage::getContent)
                .containsExactly("msg-9", "msg-8", "msg-7");
    }

    @Test
    void findFirstUnreadAdminMessage_returnsNewestDeliveredAdminReply() {
        User user = persistUser("unread-user");

        SupportMessage fromUser = new SupportMessage(user, "user asks", true);
        fromUser.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 0));
        repo.save(fromUser);

        SupportMessage olderAdmin = new SupportMessage(user, "older admin reply", false);
        olderAdmin.setStatus("DELIVERED");
        olderAdmin.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 1));
        repo.save(olderAdmin);

        SupportMessage newestAdmin = new SupportMessage(user, "newest admin reply", false);
        newestAdmin.setStatus("DELIVERED");
        newestAdmin.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 2));
        repo.save(newestAdmin);

        SupportMessage alreadyRead = new SupportMessage(user, "read reply", false);
        alreadyRead.setStatus("READ");
        alreadyRead.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 3));
        repo.save(alreadyRead);
        repo.flush();

        // Backs the notification poller, which uses this instead of downloading history.
        assertThat(repo.findFirstBySenderAndIsUserMessageFalseAndStatusOrderByTimestampDesc(user, "DELIVERED"))
                .get()
                .extracting(SupportMessage::getContent)
                .isEqualTo("newest admin reply");
    }

    private User persistUser(String username) {
        Client client = new Client();
        client.setName(username + "-client");
        entityManager.persist(client);

        User user = new User();
        user.setUsername(username);
        user.setPassword("x");
        user.setRole("XAMOPS");
        user.setClient(client);
        entityManager.persist(user);
        return user;
    }
}
