package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CspBillingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class CspBillingConfigRepositoryTest {

    @Autowired
    CspBillingConfigRepository repository;

    @Test
    void savesAndRetrievesConfig() {
        CspBillingConfig config = new CspBillingConfig();
        config.setName("Sisl");
        config.setGcpProjectId("sisl-bq-billing");
        config.setDatasetId("Xammer_Billing_Dataset");
        config.setTableId("Xammer_Dataset");
        config.setServiceAccountKey("{\"type\":\"service_account\"}");

        CspBillingConfig saved = repository.save(config);

        assertThat(saved.getId()).isNotNull();
        Optional<CspBillingConfig> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Sisl");
        assertThat(found.get().getGcpProjectId()).isEqualTo("sisl-bq-billing");
    }

    @Test
    void findAllReturnsAllConfigs() {
        CspBillingConfig c1 = new CspBillingConfig();
        c1.setName("Sisl"); c1.setGcpProjectId("p1"); c1.setDatasetId("d1");
        c1.setTableId("t1"); c1.setServiceAccountKey("{}");
        CspBillingConfig c2 = new CspBillingConfig();
        c2.setName("Other"); c2.setGcpProjectId("p2"); c2.setDatasetId("d2");
        c2.setTableId("t2"); c2.setServiceAccountKey("{}");
        repository.save(c1);
        repository.save(c2);

        List<CspBillingConfig> all = repository.findAll();
        assertThat(all).hasSize(2);
    }
}
