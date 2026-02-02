package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.sonarqube.SonarQubeMetricsDto;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DashboardDataServiceOptimizationTest {

    private DashboardDataService dashboardDataService;

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AwsClientProvider awsClientProvider;
    @Mock private GcpDataService gcpDataService;
    @Mock private CloudListService cloudListService;
    @Mock private OptimizationService optimizationService;
    @Mock private SecurityService securityService;
    @Mock private FinOpsService finOpsService;
    @Mock private ReservationService reservationService;
    @Mock private RedisCacheService redisCache;
    @Mock private ObjectMapper objectMapper;
    @Mock private CachedDataRepository cachedDataRepository;
    @Mock private CostService costService;
    @Mock private com.xammer.cloud.service.azure.AzureDashboardService azureDashboardService;
    @Mock private SonarQubeService sonarQubeService;
    @Mock private UserRepository userRepository;
    @Mock private JobExecutionHistoryRepository jobExecutionHistoryRepository;
    @Mock private DataSource masterDataSource;
    @Mock private com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

    @BeforeEach
    void setUp() {
        dashboardDataService = new DashboardDataService(
                cloudAccountRepository, awsClientProvider, gcpDataService,
                cloudListService, optimizationService, securityService,
                finOpsService, reservationService, redisCache, objectMapper,
                cachedDataRepository, costService, azureDashboardService,
                sonarQubeService, userRepository, jobExecutionHistoryRepository,
                masterDataSource, envConfig
        );
    }

    @Test
    void testGetUnifiedHealthData_ParallelAggregation() {
        // Arrange
        ClientUserDetails userDetails = mock(ClientUserDetails.class);
        when(userDetails.getUsername()).thenReturn("testuser");

        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        SonarQubeProject p1 = new SonarQubeProject();
        p1.setProjectKey("project1");
        SonarQubeProject p2 = new SonarQubeProject();
        p2.setProjectKey("project2");
        List<SonarQubeProject> projects = Arrays.asList(p1, p2);
        when(sonarQubeService.getProjectsForUser(user)).thenReturn(projects);

        SonarQubeMetricsDto m1 = new SonarQubeMetricsDto();
        m1.setBugs(10);
        m1.setVulnerabilities(5);
        m1.setCodeSmells(20);
        m1.setCoverage(80.0);

        SonarQubeMetricsDto m2 = new SonarQubeMetricsDto();
        m2.setBugs(5);
        m2.setVulnerabilities(2);
        m2.setCodeSmells(30);
        m2.setCoverage(90.0);

        when(sonarQubeService.getProjectMetrics(p1)).thenReturn(m1);
        when(sonarQubeService.getProjectMetrics(p2)).thenReturn(m2);

        // Act
        DashboardData.CodeQualitySummary summary = dashboardDataService.getUnifiedHealthData(userDetails);

        // Assert
        assertEquals(15, summary.getBugs());
        assertEquals(7, summary.getVulnerabilities());
        assertEquals(50, summary.getCodeSmells());
        assertEquals(85.0, summary.getCoverage(), 0.01);
        assertEquals(2, summary.getProjectCount());

        // Reliability rating check: (vulns > 0 || bugs > 10) -> "C"
        assertEquals("C", summary.getRating());
    }
}
