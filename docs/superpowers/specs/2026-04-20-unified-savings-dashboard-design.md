# Unified Savings Dashboard — Design Spec
**Date:** 2026-04-20  
**Feature:** Per-client unified savings view across AWS + Azure + GCP  
**Location:** BillOps Admin → ClientDetailsPage → "Savings" tab inside each client card

---

## 1. Goal

Give BillOps Admins a single place to see how much a specific client is saving (or could save) across all their cloud accounts — broken down by savings category (Spot, Rightsizing, Reservations) and by cloud provider (AWS, GCP, Azure).

All data must come from the database only — no live cloud provider API calls (no Cost Explorer hits, no Azure Cost Management calls, no GCP Billing API calls).

---

## 2. Where It Lives

`ClientDetailsPage` renders collapsible client cards, each with tabs:
- Connected Accounts
- Access Management
- **Savings** ← new tab (lazy-loaded on first click)

No new route or page needed. The client is already selected by the card context.

---

## 3. Backend

### 3.1 New Endpoint

```
GET /api/xamops/billops-admin/clients/{clientId}/savings
```

**Auth:** `ROLE_BILLOPS_ADMIN` or `ROLE_SUPER_ADMIN` (same as existing BillOpsAdminController)

**Query params:**
| Param | Type | Description |
|-------|------|-------------|
| `preset` | `7d` \| `30d` \| `90d` | Preset range (takes precedence over dates) |
| `startDate` | `yyyy-MM-dd` | Used only when `preset` is absent |
| `endDate` | `yyyy-MM-dd` | Used only when `preset` is absent |

**Logic:**
1. Load all `CloudAccount`s for the client from DB
2. Group accounts by provider: `AWS`, `GCP`, `Azure`
3. For each account, query DB sources in parallel (`CompletableFuture.allOf`):
   - **Spot savings** → `DailySpotSavings.findByAccountIdAndDateBetweenOrderByDateAsc(accountId, start, end)`
   - **EBS savings** → `EbsVolumeAction` where `accountId = X AND status = 'SUCCESS' AND initiatedAt BETWEEN start AND end`, sum `estimatedSavings`
   - **Rightsizing potential (GCP)** → `CachedData` key `GCP_RIGHTSIZING::{gcpProjectId}`, deserialize JSON, sum `monthlySavings`
   - **Rightsizing potential (Azure)** → `CachedData` key `AZURE_RIGHTSIZING::{subscriptionId}`, deserialize JSON, sum savings
   - **Rightsizing potential (AWS)** → `CachedData` key `DASHBOARD_OPT::{accountId}`, deserialize JSON, extract optimization savings
   - **Reservation potential (AWS)** → `CachedData` key `RESERVATION_ANALYSIS::{accountId}`, deserialize JSON, extract savings metrics
   - **Reservation potential (GCP)** → `CachedData` key `RESERVATION_PAGE::{gcpProjectId}`, deserialize JSON
   - **Reservation potential (Azure)** → `CachedData` key `RESERVATION_PAGE::{subscriptionId}`, deserialize JSON
4. Aggregate across all accounts per provider
5. Build daily time series from `DailySpotSavings` rows (spot + EBS only — only realized categories have daily granularity)

### 3.2 Response DTO: `ClientSavingsDashboardDto`

```java
// Realized savings (from actual DB event rows, filtered by date range)
realized: {
  spot: { aws: Double, gcp: Double, azure: Double }
  ebs:  { aws: Double }  // EbsVolumeAction SUCCESS rows
}

// Potential savings (from latest CachedData snapshots, point-in-time)
potential: {
  rightsizing:  { aws: Double, gcp: Double, azure: Double }
  reservations: { aws: Double, gcp: Double, azure: Double }
}

// Combined totals
totals: {
  realizedTotal:  Double   // spot (all) + ebs
  potentialTotal: Double   // rightsizing (all) + reservations (all)
  grandTotal:     Double   // realizedTotal + potentialTotal
}

// Daily time series (realized only — spot + EBS, grouped by date)
timeSeries: List<{
  date:       String   // "yyyy-MM-dd"
  awsSpot:    Double
  gcpSpot:    Double
  azureSpot:  Double
  awsEbs:     Double
}>

// Metadata
meta: {
  startDate:        String   // actual range used
  endDate:          String
  clientName:       String
  accountCount:     int
  cacheRefreshedAt: String   // lastUpdated from CachedData rows (latest across all keys used)
}
```

### 3.3 New Service: `ClientSavingsService`

Lives in `service/billops/ClientSavingsService.java`.

Responsibilities:
- Accept `clientId`, `startDate`, `endDate`
- Load accounts, fan out DB queries in parallel
- Deserialize `CachedData` JSON using `ObjectMapper` (reuse existing bean)
- Assemble and return `ClientSavingsDashboardDto`
- No Redis reads — `CachedData` table only for potential savings

### 3.4 Controller Addition

Add method to existing `BillOpsAdminController.java`:

```java
@GetMapping("/clients/{clientId}/savings")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public ResponseEntity<ClientSavingsDashboardDto> getClientSavings(
    @PathVariable Long clientId,
    @RequestParam(required = false) String preset,
    @RequestParam(required = false) String startDate,
    @RequestParam(required = false) String endDate,
    @AuthenticationPrincipal ClientUserDetails userDetails)
```

---

## 4. Frontend

### 4.1 New Hook: `useClientSavings`

**File:** `frontend-app/src/hooks/useClientSavings.ts`

```typescript
interface UseClientSavingsReturn {
  data: ClientSavingsDashboardData | null
  isLoading: boolean
  error: Error | null
  preset: '7d' | '30d' | '90d' | 'custom'
  startDate: string
  endDate: string
  setPreset: (p: '7d' | '30d' | '90d') => void
  setCustomRange: (start: string, end: string) => void
  refresh: () => void
}
```

- Default preset: `30d`
- Fetches lazily — call `refresh()` on first tab activation
- Does not re-fetch on preset change until user explicitly selects (debounced 300ms)

### 4.2 New Component: `ClientSavingsTab`

**File:** `frontend-app/src/components/BillOps/ClientSavingsTab.tsx`

**Layout (top to bottom):**

#### Time Range Picker
- Three preset buttons: `7d` | `30d` | `90d`  
- `Custom` option that reveals a date range picker (start + end date inputs)
- Active preset highlighted with existing `badge-enterprise-blue` class

#### KPI Cards (4 cards, same `.premium-glass` style as rest of ClientDetailsPage)
| Card | Value | Label |
|------|-------|-------|
| Total Savings | `grandTotal` | "Realized + Estimated" |
| Spot Savings | `realizedTotal` | "Realized · across clouds" |
| Rightsizing | `potential.rightsizing` sum | "~Potential · as of [cacheRefreshedAt]" |
| Reservations | `potential.reservations` sum | "~Potential · as of [cacheRefreshedAt]" |

- Potential cards show `~` prefix and "(est.)" tooltip explaining these come from cached snapshots
- `cacheRefreshedAt` shown as relative date: "as of Apr 19"

#### 3×3 Breakdown Grid
```
              Spot (Realized)    Rightsizing (~Est.)    Reservations (~Est.)
  AWS         $4,100             $1,200                 $800
  GCP         $2,800             $900                   $430
  Azure       $1,300             $700                   $200
  ─────────────────────────────────────────────────────────────
  Total       $8,200             $2,800                 $1,430
```
- Zero values shown as `—` (not `$0.00`)
- Columns with all zeros hidden entirely
- Tooltip on "~Est." header explains: "Calculated from last cached snapshot. Refresh individual cloud dashboards to update."

#### Charts
Two charts stacked vertically, using the existing chart library already in the project:

1. **Stacked Bar Chart** — daily breakdown over selected period
   - Stacks: AWS Spot | GCP Spot | Azure Spot | AWS EBS
   - X-axis: dates, Y-axis: USD savings
   - Label: "Daily Realized Savings"

2. **Cumulative Line Chart** — running total of realized savings
   - Single line: cumulative sum of all realized savings
   - X-axis: dates, Y-axis: cumulative USD
   - Label: "Cumulative Savings"

#### Empty / Loading States
- Loading: skeleton cards matching `.animate-premium-pulse` used elsewhere in ClientDetailsPage
- No data: "No savings data found for this period" with a refresh button
- Cache miss (potential data): show `—` in grid cells, tooltip "No cached data — open the cloud dashboard to populate"

### 4.3 Type Definitions

**File:** `frontend-app/src/types/clientSavings.ts`

```typescript
interface SavingsByCloud {
  aws: number
  gcp: number
  azure: number
}

interface ClientSavingsDashboardData {
  realized: {
    spot: SavingsByCloud
    ebs: { aws: number }
  }
  potential: {
    rightsizing: SavingsByCloud
    reservations: SavingsByCloud
  }
  totals: {
    realizedTotal: number
    potentialTotal: number
    grandTotal: number
  }
  timeSeries: Array<{
    date: string
    awsSpot: number
    gcpSpot: number
    azureSpot: number
    awsEbs: number
  }>
  meta: {
    startDate: string
    endDate: string
    clientName: string
    accountCount: number
    cacheRefreshedAt: string
  }
}
```

### 4.4 Integration into ClientDetailsPage

In `ClientDetailsPage.tsx`, inside the client card tab section:
- Add `"savings"` to the `expandedSection` state union type: `'accounts' | 'users' | 'savings'`
- Add "Savings" tab button alongside existing tabs
- Render `<ClientSavingsTab clientId={client.id} />` when `expandedSection === 'savings'`
- Pass `clientId` only — `ClientSavingsTab` owns all its own data fetching via `useClientSavings`

---

## 5. Data Accuracy & Labelling

| Category | Label in UI | Source | Accuracy |
|----------|-------------|--------|----------|
| Spot | "Realized" | `daily_spot_savings` rows | Exact — written by schedulers nightly |
| EBS | "Realized" | `ebs_volume_action` SUCCESS rows | Estimated at time of action |
| Rightsizing | "~Potential (est.)" | `CachedData` JSON snapshot | Stale after last dashboard refresh |
| Reservations | "~Potential (est.)" | `CachedData` JSON snapshot | Stale after last dashboard refresh |

The `~` prefix and "(est.)" label are used consistently for potential savings throughout the UI to prevent confusion with realized savings.

---

## 6. Files to Create / Modify

### New Files
| File | Purpose |
|------|---------|
| `xamops-service/.../service/billops/ClientSavingsService.java` | DB aggregation logic |
| `xamops-service/.../dto/ClientSavingsDashboardDto.java` | Response DTO |
| `frontend-app/src/hooks/useClientSavings.ts` | Data fetching hook |
| `frontend-app/src/components/BillOps/ClientSavingsTab.tsx` | Main UI component |
| `frontend-app/src/types/clientSavings.ts` | TypeScript types |

### Modified Files
| File | Change |
|------|--------|
| `xamops-service/.../controller/BillOpsAdminController.java` | Add `getClientSavings()` endpoint |
| `frontend-app/src/pages/ClientDetailsPage.tsx` | Add Savings tab + render `ClientSavingsTab` |

---

## 7. Out of Scope

- Writing new savings data to DB (schedulers already handle this)
- Forcing cache refresh from this dashboard (users must go to individual cloud dashboards)
- Exporting savings report as CSV/PDF (future enhancement)
- Per-recommendation drill-down (future enhancement)
- Platform-wide aggregate view across all clients (separate admin feature)
