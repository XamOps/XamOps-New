# Unified Savings Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Savings" tab to each client card in `ClientDetailsPage` showing realized + potential savings across AWS, GCP, and Azure — using DB data only (no live cloud API calls).

**Architecture:** One new Spring Boot service reads `daily_spot_savings`, `ebs_volume_action`, and `CachedData` tables; one new endpoint on `BillOpsAdminController` exposes the aggregated data; a new React hook + component renders KPI cards, a 3×3 breakdown grid, and two recharts charts inside the existing client card tab system.

**Tech Stack:** Java 17, Spring Boot, JPA/Hibernate, JUnit 5 + Mockito, React 18, TypeScript, recharts ^3.8.0

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `xamops-service/src/main/java/com/xammer/cloud/repository/EbsVolumeActionRepository.java` | Modify | Add date-range query method |
| `xamops-service/src/main/java/com/xammer/cloud/dto/ClientSavingsDashboardDto.java` | Create | Response DTO with inner `DailyPoint` class |
| `xamops-service/src/main/java/com/xammer/cloud/service/billops/ClientSavingsService.java` | Create | DB aggregation — spot, EBS, cached rightsizing/reservations |
| `xamops-service/src/main/java/com/xammer/cloud/controller/BillOpsAdminController.java` | Modify | Add `GET /clients/{clientId}/savings` endpoint + `resolveRange` helper |
| `xamops-service/src/test/java/com/xammer/cloud/service/billops/ClientSavingsServiceTest.java` | Create | Unit tests for service |
| `frontend-app/src/types/clientSavings.ts` | Create | TypeScript interfaces |
| `frontend-app/src/hooks/useClientSavings.ts` | Create | Data fetching hook with lazy load + preset/custom range |
| `frontend-app/src/components/BillOps/ClientSavingsTab.tsx` | Create | Full tab UI: time picker, KPI cards, 3×3 grid, charts |
| `frontend-app/src/pages/ClientDetailsPage.tsx` | Modify | Add "Savings" tab and render `ClientSavingsTab` |

---

## Task 1: Add Date-Range Query to EbsVolumeActionRepository

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/repository/EbsVolumeActionRepository.java`

- [ ] **Step 1: Read the existing repository**

Open `xamops-service/src/main/java/com/xammer/cloud/repository/EbsVolumeActionRepository.java` and confirm the existing methods.

- [ ] **Step 2: Add the new derived query method**

Add this method to the interface (after the existing `findByAccountIdAndVolumeId` method):

```java
List<EbsVolumeAction> findByAccountIdAndStatusAndInitiatedAtBetween(
    Long accountId, String status, Instant start, Instant end);
```

The full file after change:

```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.EbsVolumeAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EbsVolumeActionRepository extends JpaRepository<EbsVolumeAction, Long> {

    List<EbsVolumeAction> findByAccountIdOrderByInitiatedAtDesc(Long accountId);

    List<EbsVolumeAction> findByAccountIdAndVolumeId(Long accountId, String volumeId);

    List<EbsVolumeAction> findByAccountIdAndStatusAndInitiatedAtBetween(
        Long accountId, String status, Instant start, Instant end);

    void deleteByAccountId(Long accountId);
}
```

- [ ] **Step 3: Verify the application still compiles**

```bash
cd xamops-service && mvn compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/repository/EbsVolumeActionRepository.java
git commit -m "feat: add date-range query to EbsVolumeActionRepository"
```

---

## Task 2: Create ClientSavingsDashboardDto

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/ClientSavingsDashboardDto.java`

- [ ] **Step 1: Create the DTO file**

```java
package com.xammer.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClientSavingsDashboardDto {

    // Realized savings (from actual DB rows in the requested date range)
    private double realizedSpotAws;
    private double realizedSpotGcp;
    private double realizedSpotAzure;
    private double realizedEbsAws;

    // Potential savings (from latest CachedData snapshots — point-in-time)
    private double potentialRightsizingAws;
    private double potentialRightsizingGcp;
    private double potentialRightsizingAzure;
    private double potentialReservationsAws;
    private double potentialReservationsGcp;
    private double potentialReservationsAzure;

    // Aggregated totals
    private double realizedTotal;
    private double potentialTotal;
    private double grandTotal;

    // Daily time series (realized only — spot + EBS have daily DB rows)
    private List<DailyPoint> timeSeries;

    // Metadata
    private String startDate;
    private String endDate;
    private String clientName;
    private int accountCount;
    private String cacheRefreshedAt; // ISO date of oldest CachedData snapshot used

    @Data
    public static class DailyPoint {
        private final String date;
        private final double awsSpot;
        private final double gcpSpot;
        private final double azureSpot;
        private final double awsEbs;
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd xamops-service && mvn compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/ClientSavingsDashboardDto.java
git commit -m "feat: add ClientSavingsDashboardDto"
```

---

## Task 3: Create ClientSavingsService

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/billops/ClientSavingsService.java`
- Create: `xamops-service/src/test/java/com/xammer/cloud/service/billops/ClientSavingsServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create the directory first:
```bash
mkdir -p xamops-service/src/test/java/com/xammer/cloud/service/billops
```

Create `xamops-service/src/test/java/com/xammer/cloud/service/billops/ClientSavingsServiceTest.java`:

```java
package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientSavingsServiceTest {

    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock DailySpotSavingsRepository dailySpotSavingsRepository;
    @Mock EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock CachedDataRepository cachedDataRepository;

    @InjectMocks ClientSavingsService service;

    @BeforeEach
    void setUp() {
        // Inject real ObjectMapper (not a mock — it's used for JSON parsing)
        service.objectMapper = new ObjectMapper();
    }

    private CloudAccount makeAccount(Long id, String provider, String providerAccountId) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider(provider);
        // Set the provider-specific ID field
        if ("AWS".equals(provider)) a.setAwsAccountId(providerAccountId);
        if ("GCP".equals(provider)) a.setGcpProjectId(providerAccountId);
        if ("Azure".equals(provider)) a.setAzureSubscriptionId(providerAccountId);
        Client client = new Client();
        client.setName("Acme Corp");
        a.setClient(client);
        return a;
    }

    @Test
    void getSavings_sumsSpotSavingsAcrossProviders() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");

        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws, gcp));

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        DailySpotSavings awsSaving = new DailySpotSavings();
        awsSaving.setAmountSaved(100.0);
        awsSaving.setDate(start);

        DailySpotSavings gcpSaving = new DailySpotSavings();
        gcpSaving.setAmountSaved(50.0);
        gcpSaving.setDate(start);

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(1L, start, end))
            .thenReturn(List.of(awsSaving));
        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(2L, start, end))
            .thenReturn(List.of(gcpSaving));
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getRealizedSpotAws()).isEqualTo(100.0);
        assertThat(result.getRealizedSpotGcp()).isEqualTo(50.0);
        assertThat(result.getRealizedTotal()).isEqualTo(150.0);
    }

    @Test
    void getSavings_sumsPotentialRightsizingFromCachedData() throws Exception {
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(gcp));

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
            .thenReturn(List.of());
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());

        // Simulate CachedData JSON for GCP rightsizing: array of recommendations with monthlySavings
        String rightsizingJson = "[{\"monthlySavings\": 200.0}, {\"monthlySavings\": 150.0}]";
        com.xammer.cloud.domain.CachedData cd = new com.xammer.cloud.domain.CachedData();
        cd.setCacheKey("GCP_RIGHTSIZING::my-project");
        cd.setJsonData(rightsizingJson);
        cd.setLastUpdated(java.time.LocalDateTime.now());

        when(cachedDataRepository.findById("GCP_RIGHTSIZING::my-project")).thenReturn(Optional.of(cd));
        when(cachedDataRepository.findById(argThat(k -> !k.equals("GCP_RIGHTSIZING::my-project"))))
            .thenReturn(Optional.empty());

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getPotentialRightsizingGcp()).isEqualTo(350.0);
    }

    @Test
    void getSavings_returnsZeroWhenNoCachedData() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws));

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any()))
            .thenReturn(List.of());
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        LocalDate start = LocalDate.now().minusDays(7);
        LocalDate end = LocalDate.now();

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getGrandTotal()).isEqualTo(0.0);
        assertThat(result.getTimeSeries()).isNotEmpty();
    }

    @Test
    void getSavings_timeSeriesHasOneEntryPerDay() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of());

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getTimeSeries()).hasSize(7);
        assertThat(result.getTimeSeries().get(0).getDate()).isEqualTo("2025-04-01");
        assertThat(result.getTimeSeries().get(6).getDate()).isEqualTo("2025-04-07");
    }
}
```

- [ ] **Step 2: Run the tests — verify they fail (class not found)**

```bash
cd xamops-service && mvn test -pl . -Dtest=ClientSavingsServiceTest -q 2>&1 | tail -5
```

Expected: `BUILD FAILURE` — `ClientSavingsService` does not exist yet.

- [ ] **Step 3: Create the service**

```bash
mkdir -p xamops-service/src/main/java/com/xammer/cloud/service/billops
```

Create `xamops-service/src/main/java/com/xammer/cloud/service/billops/ClientSavingsService.java`:

```java
package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClientSavingsService {

    @Autowired CloudAccountRepository cloudAccountRepository;
    @Autowired DailySpotSavingsRepository dailySpotSavingsRepository;
    @Autowired EbsVolumeActionRepository ebsVolumeActionRepository;
    @Autowired CachedDataRepository cachedDataRepository;
    @Autowired ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ClientSavingsDashboardDto getSavings(Long clientId, LocalDate startDate, LocalDate endDate) {
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);
        String clientName = accounts.isEmpty() ? "" : accounts.get(0).getClient().getName();

        Map<String, List<CloudAccount>> byProvider = accounts.stream()
            .collect(Collectors.groupingBy(a -> a.getProvider() == null ? "" : a.getProvider()));

        List<CloudAccount> awsAccounts   = byProvider.getOrDefault("AWS",   List.of());
        List<CloudAccount> gcpAccounts   = byProvider.getOrDefault("GCP",   List.of());
        List<CloudAccount> azureAccounts = byProvider.getOrDefault("Azure", List.of());

        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant   = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // Realized — Spot
        double awsSpot   = sumSpotSavings(awsAccounts,   startDate, endDate);
        double gcpSpot   = sumSpotSavings(gcpAccounts,   startDate, endDate);
        double azureSpot = sumSpotSavings(azureAccounts, startDate, endDate);

        // Realized — EBS
        double awsEbs = sumEbsSavings(awsAccounts, startInstant, endInstant);

        // Potential — Rightsizing (latest cached snapshot, not filtered by date range)
        double awsRightsizing   = sumCachedSavings(awsAccounts,   "DASHBOARD_OPT::",      "totalMonthlySavings");
        double gcpRightsizing   = sumCachedSavings(gcpAccounts,   "GCP_RIGHTSIZING::",    "monthlySavings");
        double azureRightsizing = sumCachedSavings(azureAccounts, "AZURE_RIGHTSIZING::",  "estimatedMonthlySavings");

        // Potential — Reservations (latest cached snapshot)
        double awsReservations   = sumCachedSavings(awsAccounts,   "RESERVATION_ANALYSIS::", "estimatedMonthlySavings");
        double gcpReservations   = sumCachedSavings(gcpAccounts,   "RESERVATION_PAGE::",     "totalMonthlySavings");
        double azureReservations = sumCachedSavings(azureAccounts, "RESERVATION_PAGE::",     "totalMonthlySavings");

        double realizedTotal  = awsSpot + gcpSpot + azureSpot + awsEbs;
        double potentialTotal = awsRightsizing + gcpRightsizing + azureRightsizing
                              + awsReservations + gcpReservations + azureReservations;

        List<ClientSavingsDashboardDto.DailyPoint> timeSeries =
            buildTimeSeries(awsAccounts, gcpAccounts, azureAccounts,
                            startDate, endDate, startInstant, endInstant);

        String cacheRefreshedAt = findOldestCacheDate(accounts);

        return ClientSavingsDashboardDto.builder()
            .realizedSpotAws(awsSpot)
            .realizedSpotGcp(gcpSpot)
            .realizedSpotAzure(azureSpot)
            .realizedEbsAws(awsEbs)
            .potentialRightsizingAws(awsRightsizing)
            .potentialRightsizingGcp(gcpRightsizing)
            .potentialRightsizingAzure(azureRightsizing)
            .potentialReservationsAws(awsReservations)
            .potentialReservationsGcp(gcpReservations)
            .potentialReservationsAzure(azureReservations)
            .realizedTotal(realizedTotal)
            .potentialTotal(potentialTotal)
            .grandTotal(realizedTotal + potentialTotal)
            .timeSeries(timeSeries)
            .startDate(startDate.toString())
            .endDate(endDate.toString())
            .clientName(clientName)
            .accountCount(accounts.size())
            .cacheRefreshedAt(cacheRefreshedAt)
            .build();
    }

    private double sumSpotSavings(List<CloudAccount> accounts, LocalDate start, LocalDate end) {
        return accounts.stream()
            .mapToDouble(a -> dailySpotSavingsRepository
                .findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .stream()
                .mapToDouble(r -> r.getAmountSaved() != null ? r.getAmountSaved() : 0.0)
                .sum())
            .sum();
    }

    private double sumEbsSavings(List<CloudAccount> accounts, Instant start, Instant end) {
        return accounts.stream()
            .mapToDouble(a -> ebsVolumeActionRepository
                .findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", start, end)
                .stream()
                .mapToDouble(r -> r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0)
                .sum())
            .sum();
    }

    private double sumCachedSavings(List<CloudAccount> accounts, String keyPrefix, String savingsField) {
        return accounts.stream()
            .mapToDouble(a -> {
                String key = keyPrefix + a.getProviderAccountId();
                return cachedDataRepository.findById(key)
                    .map(cd -> extractSavingsFromJson(cd.getJsonData(), savingsField))
                    .orElse(0.0);
            })
            .sum();
    }

    private double extractSavingsFromJson(String json, String field) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                double sum = 0.0;
                for (JsonNode item : root) {
                    sum += item.path(field).asDouble(0.0);
                }
                return sum;
            }
            return root.path(field).asDouble(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private List<ClientSavingsDashboardDto.DailyPoint> buildTimeSeries(
            List<CloudAccount> aws, List<CloudAccount> gcp, List<CloudAccount> azure,
            LocalDate start, LocalDate end, Instant startInstant, Instant endInstant) {

        Map<LocalDate, double[]> byDate = new TreeMap<>();
        // Index: 0=awsSpot, 1=gcpSpot, 2=azureSpot, 3=awsEbs
        LocalDate cur = start;
        while (!cur.isAfter(end)) {
            byDate.put(cur, new double[]{0, 0, 0, 0});
            cur = cur.plusDays(1);
        }

        for (CloudAccount a : aws) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[0] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : gcp) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[1] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : azure) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[2] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : aws) {
            ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", startInstant, endInstant)
                .forEach(r -> {
                    LocalDate d = r.getInitiatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    double[] arr = byDate.getOrDefault(d, new double[]{0, 0, 0, 0});
                    arr[3] += r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0;
                });
        }

        return byDate.entrySet().stream()
            .map(e -> new ClientSavingsDashboardDto.DailyPoint(
                e.getKey().toString(),
                e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
            .collect(Collectors.toList());
    }

    private String findOldestCacheDate(List<CloudAccount> accounts) {
        return accounts.stream()
            .flatMap(a -> List.of(
                "GCP_RIGHTSIZING::" + a.getProviderAccountId(),
                "AZURE_RIGHTSIZING::" + a.getProviderAccountId(),
                "DASHBOARD_OPT::" + a.getProviderAccountId(),
                "RESERVATION_ANALYSIS::" + a.getProviderAccountId(),
                "RESERVATION_PAGE::" + a.getProviderAccountId()
            ).stream())
            .map(key -> cachedDataRepository.findById(key))
            .filter(Optional::isPresent)
            .map(opt -> opt.get().getLastUpdated().toLocalDate())
            .min(LocalDate::compareTo)
            .map(LocalDate::toString)
            .orElse(null);
    }
}
```

- [ ] **Step 4: Run the tests — verify they pass**

```bash
cd xamops-service && mvn test -pl . -Dtest=ClientSavingsServiceTest -q 2>&1 | tail -10
```

Expected: `Tests run: 4, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/billops/ClientSavingsService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/billops/ClientSavingsServiceTest.java
git commit -m "feat: add ClientSavingsService with DB-only savings aggregation"
```

---

## Task 4: Add Savings Endpoint to BillOpsAdminController

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/BillOpsAdminController.java`

- [ ] **Step 1: Add the `ClientSavingsService` field**

In `BillOpsAdminController.java`, locate the existing `@Autowired` field block at the top of the class and add:

```java
@Autowired
private ClientSavingsService clientSavingsService;
```

- [ ] **Step 2: Add the endpoint and helper method**

Add these two methods at the end of the class, before the closing `}`:

```java
@GetMapping("/clients/{clientId}/savings")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public ResponseEntity<ClientSavingsDashboardDto> getClientSavings(
        @PathVariable Long clientId,
        @RequestParam(required = false) String preset,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate,
        @AuthenticationPrincipal ClientUserDetails userDetails) {

    LocalDate[] range = resolveSavingsRange(preset, startDate, endDate);
    ClientSavingsDashboardDto dto = clientSavingsService.getSavings(clientId, range[0], range[1]);
    return ResponseEntity.ok(dto);
}

private LocalDate[] resolveSavingsRange(String preset, String startDate, String endDate) {
    LocalDate end = LocalDate.now();
    if (preset != null) {
        return switch (preset) {
            case "7d"  -> new LocalDate[]{ end.minusDays(7),  end };
            case "90d" -> new LocalDate[]{ end.minusDays(90), end };
            default    -> new LocalDate[]{ end.minusDays(30), end };
        };
    }
    if (startDate != null && endDate != null) {
        return new LocalDate[]{ LocalDate.parse(startDate), LocalDate.parse(endDate) };
    }
    return new LocalDate[]{ end.minusDays(30), end };
}
```

Also add this import at the top of the file if not already present:

```java
import com.xammer.cloud.service.billops.ClientSavingsService;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import java.time.LocalDate;
```

- [ ] **Step 3: Verify compilation**

```bash
cd xamops-service && mvn compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/BillOpsAdminController.java
git commit -m "feat: add GET /clients/{clientId}/savings endpoint"
```

---

## Task 5: Frontend TypeScript Types

**Files:**
- Create: `frontend-app/src/types/clientSavings.ts`

- [ ] **Step 1: Create the types file**

```typescript
// frontend-app/src/types/clientSavings.ts

export type SavingsPreset = '7d' | '30d' | '90d' | 'custom'

export interface SavingsByCloud {
  aws: number
  gcp: number
  azure: number
}

export interface DailyPoint {
  date: string
  awsSpot: number
  gcpSpot: number
  azureSpot: number
  awsEbs: number
}

export interface ClientSavingsDashboardData {
  // Realized (DB rows in requested date range)
  realizedSpotAws: number
  realizedSpotGcp: number
  realizedSpotAzure: number
  realizedEbsAws: number

  // Potential (latest CachedData snapshot)
  potentialRightsizingAws: number
  potentialRightsizingGcp: number
  potentialRightsizingAzure: number
  potentialReservationsAws: number
  potentialReservationsGcp: number
  potentialReservationsAzure: number

  // Totals
  realizedTotal: number
  potentialTotal: number
  grandTotal: number

  // Daily time series (realized only)
  timeSeries: DailyPoint[]

  // Metadata
  startDate: string
  endDate: string
  clientName: string
  accountCount: number
  cacheRefreshedAt: string | null
}
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors related to `clientSavings.ts`.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/types/clientSavings.ts
git commit -m "feat: add ClientSavingsDashboardData TypeScript types"
```

---

## Task 6: Frontend Hook — useClientSavings

**Files:**
- Create: `frontend-app/src/hooks/useClientSavings.ts`

- [ ] **Step 1: Create the hook**

```typescript
// frontend-app/src/hooks/useClientSavings.ts

import { useState, useCallback, useRef } from 'react'
import apiClient from '../utils/apiClient'
import { ClientSavingsDashboardData, SavingsPreset } from '../types/clientSavings'

interface UseClientSavingsReturn {
  data: ClientSavingsDashboardData | null
  isLoading: boolean
  error: string | null
  preset: SavingsPreset
  customStart: string
  customEnd: string
  setPreset: (p: SavingsPreset) => void
  setCustomRange: (start: string, end: string) => void
  load: () => void
}

export function useClientSavings(clientId: number): UseClientSavingsReturn {
  const [data, setData]         = useState<ClientSavingsDashboardData | null>(null)
  const [isLoading, setLoading] = useState(false)
  const [error, setError]       = useState<string | null>(null)
  const [preset, setPresetState] = useState<SavingsPreset>('30d')
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd]     = useState('')
  // Track whether the tab has been activated at least once (lazy load)
  const hasLoaded = useRef(false)

  const fetchData = useCallback(async (p: SavingsPreset, start: string, end: string) => {
    setLoading(true)
    setError(null)
    try {
      const params = p !== 'custom'
        ? { preset: p }
        : { startDate: start, endDate: end }
      const response = await apiClient.get<ClientSavingsDashboardData>(
        `/api/xamops/billops-admin/clients/${clientId}/savings`,
        { params }
      )
      setData(response.data)
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? 'Failed to load savings data')
    } finally {
      setLoading(false)
    }
  }, [clientId])

  // Called on first tab activation — triggers initial fetch
  const load = useCallback(() => {
    if (!hasLoaded.current) {
      hasLoaded.current = true
    }
    fetchData(preset, customStart, customEnd)
  }, [fetchData, preset, customStart, customEnd])

  const setPreset = useCallback((p: SavingsPreset) => {
    setPresetState(p)
    if (hasLoaded.current) {
      fetchData(p, customStart, customEnd)
    }
  }, [fetchData, customStart, customEnd])

  const setCustomRange = useCallback((start: string, end: string) => {
    setCustomStart(start)
    setCustomEnd(end)
    setPresetState('custom')
    if (hasLoaded.current) {
      fetchData('custom', start, end)
    }
  }, [fetchData])

  return {
    data, isLoading, error,
    preset, customStart, customEnd,
    setPreset, setCustomRange, load
  }
}
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```

Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/hooks/useClientSavings.ts
git commit -m "feat: add useClientSavings hook with lazy load and preset/custom range"
```

---

## Task 7: Frontend Component — ClientSavingsTab

**Files:**
- Create: `frontend-app/src/components/BillOps/ClientSavingsTab.tsx`

- [ ] **Step 1: Create the component**

```tsx
// frontend-app/src/components/BillOps/ClientSavingsTab.tsx

import React, { useEffect } from 'react'
import {
  BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer
} from 'recharts'
import { useClientSavings } from '../../hooks/useClientSavings'
import { SavingsPreset } from '../../types/clientSavings'

interface Props {
  clientId: number
}

const fmt = (n: number) =>
  n === 0 ? '—' : `$${n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`

export default function ClientSavingsTab({ clientId }: Props) {
  const {
    data, isLoading, error,
    preset, customStart, customEnd,
    setPreset, setCustomRange, load
  } = useClientSavings(clientId)

  // Lazy load on first render of this tab
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <SavingsSkeleton />
  if (error)     return <div className="p-4 text-red-500 text-sm">{error}</div>
  if (!data)     return null

  // Build cumulative series for the line chart
  let cumulative = 0
  const cumulativeData = data.timeSeries.map(p => {
    cumulative += p.awsSpot + p.gcpSpot + p.azureSpot + p.awsEbs
    return { date: p.date, cumulative }
  })

  const totalRightsizing =
    data.potentialRightsizingAws + data.potentialRightsizingGcp + data.potentialRightsizingAzure
  const totalReservations =
    data.potentialReservationsAws + data.potentialReservationsGcp + data.potentialReservationsAzure

  return (
    <div className="p-4 space-y-6">

      {/* ── Time Range Picker ── */}
      <div className="flex items-center gap-2 flex-wrap">
        {(['7d', '30d', '90d'] as SavingsPreset[]).map(p => (
          <button
            key={p}
            onClick={() => setPreset(p)}
            className={`px-3 py-1 text-xs rounded-full border transition-colors ${
              preset === p
                ? 'bg-blue-600 text-white border-blue-600'
                : 'bg-white text-gray-600 border-gray-300 hover:border-blue-400'
            }`}
          >
            {p === '7d' ? 'Last 7 days' : p === '30d' ? 'Last 30 days' : 'Last 90 days'}
          </button>
        ))}
        <span className="text-gray-400 text-xs mx-1">|</span>
        <span className="text-xs text-gray-500">Custom:</span>
        <input
          type="date"
          value={customStart}
          max={customEnd || undefined}
          onChange={e => setCustomRange(e.target.value, customEnd)}
          className="text-xs border border-gray-300 rounded px-2 py-1"
        />
        <span className="text-xs text-gray-400">→</span>
        <input
          type="date"
          value={customEnd}
          min={customStart || undefined}
          onChange={e => setCustomRange(customStart, e.target.value)}
          className="text-xs border border-gray-300 rounded px-2 py-1"
        />
      </div>

      {/* ── KPI Cards ── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <KpiCard
          label="Total Savings"
          value={fmt(data.grandTotal)}
          sub="Realized + Estimated"
          realized
        />
        <KpiCard
          label="Spot Savings"
          value={fmt(data.realizedTotal)}
          sub="Realized · across clouds"
          realized
        />
        <KpiCard
          label="Rightsizing"
          value={`~${fmt(totalRightsizing)}`}
          sub={data.cacheRefreshedAt ? `Potential · as of ${data.cacheRefreshedAt}` : 'Potential (est.)'}
          realized={false}
        />
        <KpiCard
          label="Reservations"
          value={`~${fmt(totalReservations)}`}
          sub={data.cacheRefreshedAt ? `Potential · as of ${data.cacheRefreshedAt}` : 'Potential (est.)'}
          realized={false}
        />
      </div>

      {/* ── 3×3 Breakdown Grid ── */}
      <div>
        <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
          Savings Breakdown
        </h4>
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="bg-gray-50 text-gray-500 text-xs">
                <th className="text-left p-2 border border-gray-200"></th>
                <th className="text-right p-2 border border-gray-200">Spot (Realized)</th>
                <th className="text-right p-2 border border-gray-200">
                  Rightsizing
                  <span className="text-gray-400 ml-1 cursor-help" title="From last cached dashboard refresh. Open cloud dashboards to update.">~est.</span>
                </th>
                <th className="text-right p-2 border border-gray-200">
                  Reservations
                  <span className="text-gray-400 ml-1 cursor-help" title="From last cached dashboard refresh. Open cloud dashboards to update.">~est.</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <GridRow
                label="AWS"
                spot={data.realizedSpotAws + data.realizedEbsAws}
                rightsizing={data.potentialRightsizingAws}
                reservations={data.potentialReservationsAws}
              />
              <GridRow
                label="GCP"
                spot={data.realizedSpotGcp}
                rightsizing={data.potentialRightsizingGcp}
                reservations={data.potentialReservationsGcp}
              />
              <GridRow
                label="Azure"
                spot={data.realizedSpotAzure}
                rightsizing={data.potentialRightsizingAzure}
                reservations={data.potentialReservationsAzure}
              />
              <tr className="bg-gray-50 font-semibold text-xs">
                <td className="p-2 border border-gray-200 text-gray-700">Total</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">
                  {fmt(data.realizedSpotAws + data.realizedSpotGcp + data.realizedSpotAzure + data.realizedEbsAws)}
                </td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalRightsizing)}</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalReservations)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* ── Charts ── */}
      <div className="space-y-6">
        {/* Stacked Bar — daily realized savings */}
        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
            Daily Realized Savings
          </h4>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data.timeSeries} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={d => d.slice(5)} />
              <YAxis tick={{ fontSize: 10 }} tickFormatter={v => `$${v}`} />
              <Tooltip formatter={(v: number) => [`$${v.toFixed(2)}`, undefined]} />
              <Legend iconSize={10} wrapperStyle={{ fontSize: 11 }} />
              <Bar dataKey="awsSpot"   name="AWS Spot"   stackId="a" fill="#f59e0b" />
              <Bar dataKey="gcpSpot"   name="GCP Spot"   stackId="a" fill="#3b82f6" />
              <Bar dataKey="azureSpot" name="Azure Spot" stackId="a" fill="#8b5cf6" />
              <Bar dataKey="awsEbs"    name="AWS EBS"    stackId="a" fill="#10b981" />
            </BarChart>
          </ResponsiveContainer>
        </div>

        {/* Line — cumulative realized savings */}
        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
            Cumulative Savings
          </h4>
          <ResponsiveContainer width="100%" height={160}>
            <LineChart data={cumulativeData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={d => d.slice(5)} />
              <YAxis tick={{ fontSize: 10 }} tickFormatter={v => `$${v}`} />
              <Tooltip formatter={(v: number) => [`$${v.toFixed(2)}`, 'Cumulative']} />
              <Line
                type="monotone"
                dataKey="cumulative"
                stroke="#2563eb"
                strokeWidth={2}
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  )
}

// ── Sub-components ──────────────────────────────────────────────────────────

function KpiCard({ label, value, sub, realized }: {
  label: string; value: string; sub: string; realized: boolean
}) {
  return (
    <div className="premium-glass rounded-lg p-3 border border-gray-100">
      <div className="text-xs text-gray-500 mb-1">{label}</div>
      <div className={`text-lg font-bold ${realized ? 'text-gray-900' : 'text-blue-700'}`}>
        {value}
      </div>
      <div className="text-xs text-gray-400 mt-1">{sub}</div>
    </div>
  )
}

function GridRow({ label, spot, rightsizing, reservations }: {
  label: string; spot: number; rightsizing: number; reservations: number
}) {
  return (
    <tr className="hover:bg-gray-50 text-xs">
      <td className="p-2 border border-gray-200 font-medium text-gray-700">{label}</td>
      <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(spot)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(rightsizing)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(reservations)}</td>
    </tr>
  )
}

function SavingsSkeleton() {
  return (
    <div className="p-4 space-y-4 animate-premium-pulse">
      <div className="grid grid-cols-4 gap-3">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="h-20 bg-gray-100 rounded-lg" />
        ))}
      </div>
      <div className="h-32 bg-gray-100 rounded-lg" />
      <div className="h-48 bg-gray-100 rounded-lg" />
    </div>
  )
}
```

- [ ] **Step 2: Verify TypeScript compilation**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```

Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/components/BillOps/ClientSavingsTab.tsx
git commit -m "feat: add ClientSavingsTab component with KPI cards, grid, and charts"
```

---

## Task 8: Integrate Savings Tab into ClientDetailsPage

**Files:**
- Modify: `frontend-app/src/pages/ClientDetailsPage.tsx`

- [ ] **Step 1: Add the import**

At the top of `ClientDetailsPage.tsx`, add:

```tsx
import ClientSavingsTab from '../components/BillOps/ClientSavingsTab'
```

- [ ] **Step 2: Update the `expandedSection` type**

Find the existing state declaration for `expandedSection`. It currently looks like:

```tsx
const [expandedSection, setExpandedSection] = useState<'accounts' | 'users'>('accounts')
```

Change it to:

```tsx
const [expandedSection, setExpandedSection] = useState<'accounts' | 'users' | 'savings'>('accounts')
```

- [ ] **Step 3: Add the Savings tab button**

Find the tab buttons block inside the client card. It will contain buttons setting `expandedSection` to `'accounts'` and `'users'`. Add the Savings tab button after the existing two:

```tsx
<button
  onClick={() => setExpandedSection('savings')}
  className={`px-3 py-1 text-xs rounded-md transition-colors ${
    expandedSection === 'savings'
      ? 'bg-blue-50 text-blue-700 font-medium'
      : 'text-gray-500 hover:text-gray-700'
  }`}
>
  Savings
</button>
```

- [ ] **Step 4: Render ClientSavingsTab when selected**

Find the conditional rendering block that checks `expandedSection`. It will look like:

```tsx
{expandedSection === 'accounts' && ( ... )}
{expandedSection === 'users' && ( ... )}
```

Add after the existing two conditions:

```tsx
{expandedSection === 'savings' && (
  <ClientSavingsTab clientId={client.id} />
)}
```

- [ ] **Step 5: Verify TypeScript compilation**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 6: Start the dev server and test manually**

```bash
cd frontend-app && npm run dev
```

Open the BillOps Admin dashboard in the browser at the local dev URL.

Manual test checklist:
- [ ] Expand any client card — "Savings" tab appears next to "Connected Accounts" and "Access Management"
- [ ] Click "Savings" tab — loading skeleton appears, then data loads
- [ ] Switch between 7d / 30d / 90d presets — data refreshes
- [ ] Enter a custom date range — data refreshes on second date entry
- [ ] Client with no spot savings shows `—` in grid cells (not `$0.00`)
- [ ] Switching to "Connected Accounts" and back to "Savings" does NOT re-fetch (lazy load)

- [ ] **Step 7: Commit**

```bash
git add frontend-app/src/pages/ClientDetailsPage.tsx
git commit -m "feat: integrate Savings tab into ClientDetailsPage client cards"
```

---

## Self-Review

**Spec coverage:**
- ✅ Per-client drill-down — `clientId` path param, loads client's accounts only
- ✅ Preset time ranges (7d/30d/90d) + custom date picker — `resolveSavingsRange` + hook
- ✅ KPI cards by category (4 cards) — `KpiCard` component
- ✅ 3×3 breakdown grid (cloud × category) — `GridRow` + table
- ✅ Stacked bar + cumulative line charts — recharts `BarChart` + `LineChart`
- ✅ DB-only data sources — `DailySpotSavings`, `EbsVolumeAction`, `CachedData` (no live API)
- ✅ Realized vs potential labelling — `~` prefix, "est." label, `cacheRefreshedAt` shown
- ✅ Lazy load — `hasLoaded` ref, `load()` called only on first tab activation
- ✅ Zero values shown as `—` — `fmt()` helper
- ✅ Auth guard — `@PreAuthorize` matches existing controller pattern
- ✅ `animate-premium-pulse` skeleton — matches existing ClientDetailsPage style

**Type consistency check:**
- `DailyPoint` fields (`awsSpot`, `gcpSpot`, `azureSpot`, `awsEbs`) match across DTO → types → hook → component ✅
- `ClientSavingsDashboardDto` field names match `ClientSavingsDashboardData` TypeScript interface ✅
- `useClientSavings` return type matches usage in `ClientSavingsTab` ✅
