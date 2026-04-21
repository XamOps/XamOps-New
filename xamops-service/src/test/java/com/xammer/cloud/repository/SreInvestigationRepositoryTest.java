package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SreInvestigationRepositoryTest {

    @Autowired
    private SreInvestigationRepository repo;

    @Test
    void findByClientId_returnsOnlyMatchingRows() {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(42L);
        inv.setResourceType(SreInvestigation.ResourceType.K8S_POD);
        inv.setResourceId("pod-abc");
        inv.setTriggerType(SreInvestigation.TriggerType.MANUAL);
        repo.save(inv);

        List<SreInvestigation> results = repo.findByClientIdOrderByCreatedAtDesc(42L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResourceId()).isEqualTo("pod-abc");
    }

    @Test
    void findByClientIdAndStatus_filtersCorrectly() {
        SreInvestigation inv = new SreInvestigation();
        inv.setClientId(1L);
        inv.setResourceType(SreInvestigation.ResourceType.EC2);
        inv.setResourceId("i-123");
        inv.setTriggerType(SreInvestigation.TriggerType.AUTO);
        inv.setStatus(SreInvestigation.InvestigationStatus.RUNNING);
        repo.save(inv);

        List<SreInvestigation> running = repo.findByClientIdAndStatus(1L, SreInvestigation.InvestigationStatus.RUNNING);
        assertThat(running).hasSize(1);
    }
}
