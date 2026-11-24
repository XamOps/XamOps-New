package com.xammer.cloud.repository;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
/** The duplicate-project check must key on gcp_project_id only; a row with a null aws id must not match another project. */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class CloudAccountGcpProjectExistsTest {
    @Autowired
    CloudAccountRepository repository;
    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;
    @Test
    void nullAwsRowDoesNotMatchADifferentProjectButTheSameProjectDoes() {
        Client client = new Client();
        client.setName("c1");
        em.persist(client);
        CloudAccount existing = new CloudAccount();
        existing.setAccountName("p1");
        existing.setProvider("GCP");
        existing.setGcpProjectId("p1");
        existing.setClient(client);
        repository.saveAndFlush(existing);
        assertThat(existing.getAwsAccountId()).isNull();
        assertThat(repository.existsByGcpProjectId("p2")).isFalse();
        assertThat(repository.existsByGcpProjectId("p1")).isTrue();
    }
}
