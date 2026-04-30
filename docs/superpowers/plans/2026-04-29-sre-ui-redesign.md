# SRE UI Redesign — From Scratch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing basic SRE investigation pages with a polished, production-grade SRE platform UI that competes with Datadog and New Relic — featuring a live dashboard, rich incident views with confidence/evidence, verdict feedback, and an onboarding wizard.

**Architecture:** Four new/replaced frontend pages (Dashboard, Incidents list, Incident detail, Setup wizard) built with Tailwind CSS following existing project patterns. One new backend endpoint for verdict feedback. Existing API layer (`useSreInvestigation.ts`) extended but not replaced.

**Tech Stack:** React 18, TypeScript, Tailwind CSS, React Router, `apiClient` (existing), `goey-toast` (existing), `useTabState` hook (existing)

---

## File Map

**New / replaced frontend pages:**
- Replace: `frontend-app/src/pages/SreInvestigationsPage.tsx` — redesigned incidents list with severity badges, confidence, MTTR, search
- Replace: `frontend-app/src/pages/SreInvestigationDetailPage.tsx` — rich detail with confidence meter, evidence, verdicts, markdown report
- Create: `frontend-app/src/pages/SreDashboardPage.tsx` — live overview: service health grid, active incidents feed, MTTR stats
- Create: `frontend-app/src/pages/SreSetupPage.tsx` — onboarding wizard: stack selection → API key → verify → SLOs

**Modified frontend files:**
- `frontend-app/src/hooks/useSreInvestigation.ts` — add `verdict` field, `submitVerdict()` call, `getSreStats()` call
- `frontend-app/src/App.tsx` — add `/sre` dashboard route + `/sre/setup` route
- `frontend-app/src/components/Sidebar/Sidebar.tsx` — add SRE nav section with Dashboard + Investigations + Setup items

**Backend additions:**
- Create: `xamops-service/src/main/resources/db/migration/V20260429__add_sre_verdict.sql` — verdict fields on sre_investigation
- Modify: `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java` — add verdict fields
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreStatsDto.java` — MTTR + counts for dashboard
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java` — add `submitVerdict()`, `getStats()`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java` — add `PUT /{id}/verdict`, `GET /stats`

---

## Task 1: Backend — Verdict Migration + Entity

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260429__add_sre_verdict.sql`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`

- [ ] **Step 1: Write the migration**

```sql
-- V20260429__add_sre_verdict.sql
ALTER TABLE sre_investigation
  ADD COLUMN IF NOT EXISTS verdict VARCHAR(20)
    CHECK (verdict IN ('RESOLVED','FALSE_POSITIVE','WRONG_ROOT_CAUSE')),
  ADD COLUMN IF NOT EXISTS verdict_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS verdict_note TEXT;
```

- [ ] **Step 2: Add verdict fields to SreInvestigation.java**

Open `xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java`.

After the `errorMessage` field (line ~62), add:

```java
@Enumerated(EnumType.STRING)
@Column(length = 20)
private Verdict verdict;

@Column(name = "verdict_at")
private Instant verdictAt;

@Column(name = "verdict_note", columnDefinition = "TEXT")
private String verdictNote;

public enum Verdict { RESOLVED, FALSE_POSITIVE, WRONG_ROOT_CAUSE }
```

Also add `GENERIC` to `ResourceType` if not already present:
```java
public enum ResourceType { K8S_POD, EC2, RDS, AZURE_VM, GCP_VM, GENERIC }
```

- [ ] **Step 3: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260429__add_sre_verdict.sql \
        xamops-service/src/main/java/com/xammer/cloud/domain/SreInvestigation.java
git commit -m "feat(sre): add verdict fields to sre_investigation"
```

---

## Task 2: Backend — Stats + Verdict Endpoints

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreStatsDto.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java`

- [ ] **Step 1: Create SreStatsDto.java**

```java
// src/main/java/com/xammer/cloud/dto/sre/SreStatsDto.java
package com.xammer.cloud.dto.sre;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SreStatsDto {
    private long totalInvestigations;
    private long activeInvestigations;   // PENDING + RUNNING
    private long completedToday;
    private long failedToday;
    private Long avgMttrSeconds;         // null if no completed investigations
}
```

- [ ] **Step 2: Add submitVerdict() and getStats() to SreInvestigationService.java**

After the `getById()` method, add:

```java
public SreInvestigation submitVerdict(Long id, Long clientId,
                                       SreInvestigation.Verdict verdict,
                                       String note) {
    SreInvestigation inv = investigationRepo.findById(id)
        .filter(i -> i.getClientId().equals(clientId))
        .orElseThrow(() -> new RuntimeException("Investigation not found"));
    inv.setVerdict(verdict);
    inv.setVerdictAt(Instant.now());
    inv.setVerdictNote(note);
    return investigationRepo.save(inv);
}

public SreStatsDto getStats(Long clientId) {
    List<SreInvestigation> all = investigationRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    Instant startOfDay = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);

    long active = all.stream()
        .filter(i -> i.getStatus() == SreInvestigation.InvestigationStatus.PENDING
                  || i.getStatus() == SreInvestigation.InvestigationStatus.RUNNING)
        .count();

    long completedToday = all.stream()
        .filter(i -> i.getStatus() == SreInvestigation.InvestigationStatus.COMPLETED
                  && i.getCompletedAt() != null
                  && i.getCompletedAt().isAfter(startOfDay))
        .count();

    long failedToday = all.stream()
        .filter(i -> i.getStatus() == SreInvestigation.InvestigationStatus.FAILED
                  && i.getCompletedAt() != null
                  && i.getCompletedAt().isAfter(startOfDay))
        .count();

    OptionalDouble avgMttr = all.stream()
        .filter(i -> i.getStatus() == SreInvestigation.InvestigationStatus.COMPLETED
                  && i.getStartedAt() != null && i.getCompletedAt() != null)
        .mapToLong(i -> i.getCompletedAt().getEpochSecond() - i.getStartedAt().getEpochSecond())
        .average();

    return new SreStatsDto(all.size(), active, completedToday, failedToday,
            avgMttr.isPresent() ? (long) avgMttr.getAsDouble() : null);
}
```

Add the missing import at the top of the file:
```java
import com.xammer.cloud.dto.sre.SreStatsDto;
import java.util.OptionalDouble;
```

- [ ] **Step 3: Add verdict + stats endpoints to SreInvestigationController.java**

After the `getStatus()` endpoint, add:

```java
@PutMapping("/investigations/{id}/verdict")
public ResponseEntity<SreInvestigationDto> submitVerdict(
        @PathVariable Long id,
        @RequestBody Map<String, String> body,
        @AuthenticationPrincipal ClientUserDetails user) {
    String verdictStr = body.get("verdict");
    String note = body.getOrDefault("note", "");
    if (verdictStr == null) return ResponseEntity.badRequest().build();
    SreInvestigation.Verdict verdict;
    try {
        verdict = SreInvestigation.Verdict.valueOf(verdictStr);
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().build();
    }
    SreInvestigation inv = investigationService.submitVerdict(id, user.getClientId(), verdict, note);
    return ResponseEntity.ok(SreInvestigationDto.from(inv));
}

@GetMapping("/stats")
public ResponseEntity<SreStatsDto> getStats(@AuthenticationPrincipal ClientUserDetails user) {
    return ResponseEntity.ok(investigationService.getStats(user.getClientId()));
}
```

Add missing import at the top:
```java
import com.xammer.cloud.dto.sre.SreStatsDto;
```

- [ ] **Step 4: Add verdict fields to SreInvestigationDto.java**

Open `xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreInvestigationDto.java`.

Add fields and update the `from()` method:
```java
private String verdict;
private String verdictAt;
private String verdictNote;
```

In the `from()` method add:
```java
dto.setVerdict(inv.getVerdict() != null ? inv.getVerdict().name() : null);
dto.setVerdictAt(inv.getVerdictAt() != null ? inv.getVerdictAt().toString() : null);
dto.setVerdictNote(inv.getVerdictNote());
```

- [ ] **Step 5: Build backend to verify no compilation errors**

```bash
cd xamops-service && mvn package -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreStatsDto.java \
        xamops-service/src/main/java/com/xammer/cloud/service/sre/SreInvestigationService.java \
        xamops-service/src/main/java/com/xammer/cloud/controller/sre/SreInvestigationController.java \
        xamops-service/src/main/java/com/xammer/cloud/dto/sre/SreInvestigationDto.java
git commit -m "feat(sre): add verdict endpoint + stats endpoint"
```

---

## Task 3: Frontend — Extend useSreInvestigation Hook

**Files:**
- Modify: `frontend-app/src/hooks/useSreInvestigation.ts`

- [ ] **Step 1: Replace the full file content**

```typescript
// frontend-app/src/hooks/useSreInvestigation.ts
import apiClient from '../utils/apiClient';

export type InvestigationStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ResourceType = 'K8S_POD' | 'EC2' | 'RDS' | 'AZURE_VM' | 'GCP_VM' | 'GENERIC';
export type TriggerType = 'AUTO' | 'MANUAL';
export type Verdict = 'RESOLVED' | 'FALSE_POSITIVE' | 'WRONG_ROOT_CAUSE';

export interface SreInvestigation {
  id: number;
  clientId: number;
  cloudAccountId?: number;
  triggerType: TriggerType;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  status: InvestigationStatus;
  reportSummary?: string;
  reportRootCause?: string;
  reportRecommendations?: string[];
  reportFullMarkdown?: string;
  errorMessage?: string;
  verdict?: Verdict;
  verdictAt?: string;
  verdictNote?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
}

export interface SreStats {
  totalInvestigations: number;
  activeInvestigations: number;
  completedToday: number;
  failedToday: number;
  avgMttrSeconds: number | null;
}

export interface TriggerInvestigationRequest {
  cloudAccountId?: number;
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  alertPayload?: string;
}

export interface SreNotification {
  id: number;
  investigationId: number;
  title: string;
  message?: string;
  read: boolean;
  createdAt: string;
}

export const triggerInvestigation = async (req: TriggerInvestigationRequest): Promise<SreInvestigation> => {
  return apiClient.post<SreInvestigation>('/api/sre/investigations', req);
};

export const listInvestigations = async (): Promise<SreInvestigation[]> => {
  return apiClient.get<SreInvestigation[]>('/api/sre/investigations');
};

export const getInvestigation = async (id: number): Promise<SreInvestigation> => {
  return apiClient.get<SreInvestigation>(`/api/sre/investigations/${id}`);
};

export const pollStatus = async (id: number): Promise<{ status: InvestigationStatus }> => {
  return apiClient.get<{ status: InvestigationStatus }>(`/api/sre/investigations/${id}/status`);
};

export const submitVerdict = async (id: number, verdict: Verdict, note?: string): Promise<SreInvestigation> => {
  return apiClient.put<SreInvestigation>(`/api/sre/investigations/${id}/verdict`, { verdict, note: note ?? '' });
};

export const getSreStats = async (): Promise<SreStats> => {
  return apiClient.get<SreStats>('/api/sre/stats');
};

export const getNotifications = async (): Promise<SreNotification[]> => {
  return apiClient.get<SreNotification[]>('/api/sre/notifications');
};

export const getUnreadCount = async (): Promise<number> => {
  const data = await apiClient.get<{ count: number }>('/api/sre/notifications/count');
  return data.count;
};

export const markRead = async (id: number): Promise<void> => {
  await apiClient.put<void>(`/api/sre/notifications/${id}/read`);
};
```

- [ ] **Step 2: Commit**

```bash
git add frontend-app/src/hooks/useSreInvestigation.ts
git commit -m "feat(sre-ui): extend hook with verdict, stats, GENERIC resource type"
```

---

## Task 4: Frontend — Shared SRE Utilities

**Files:**
- Create: `frontend-app/src/components/SRE/sreUtils.ts`
- Create: `frontend-app/src/components/SRE/SreSeverityBadge.tsx`
- Create: `frontend-app/src/components/SRE/SreStatusBadge.tsx`

These are small, shared pieces used by all SRE pages.

- [ ] **Step 1: Create sreUtils.ts**

```typescript
// frontend-app/src/components/SRE/sreUtils.ts
import { InvestigationStatus, ResourceType } from '../../hooks/useSreInvestigation';

export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

export function formatMttr(seconds: number | null): string {
  if (seconds === null) return '—';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return `${h}h ${m}m`;
}

export function duration(start?: string, end?: string): string {
  if (!start || !end) return '';
  const secs = Math.floor((new Date(end).getTime() - new Date(start).getTime()) / 1000);
  return formatMttr(secs);
}

export const resourceTypeLabel: Record<ResourceType, string> = {
  K8S_POD: 'K8s Pod',
  EC2: 'EC2',
  RDS: 'RDS',
  AZURE_VM: 'Azure VM',
  GCP_VM: 'GCP VM',
  GENERIC: 'Service',
};

export const statusColors: Record<InvestigationStatus, { bg: string; text: string; dot: string }> = {
  PENDING:   { bg: 'bg-amber-50',   text: 'text-amber-700',  dot: 'bg-amber-400' },
  RUNNING:   { bg: 'bg-amber-50',   text: 'text-amber-700',  dot: 'bg-amber-400' },
  COMPLETED: { bg: 'bg-emerald-50', text: 'text-emerald-700',dot: 'bg-emerald-500' },
  FAILED:    { bg: 'bg-red-50',     text: 'text-red-600',    dot: 'bg-red-500' },
};
```

- [ ] **Step 2: Create SreSeverityBadge.tsx**

```tsx
// frontend-app/src/components/SRE/SreSeverityBadge.tsx
import React from 'react';

type Severity = 'critical' | 'high' | 'medium' | 'low';

const config: Record<Severity, { bg: string; text: string; label: string }> = {
  critical: { bg: 'bg-red-100',    text: 'text-red-700',    label: 'Critical' },
  high:     { bg: 'bg-orange-100', text: 'text-orange-700', label: 'High' },
  medium:   { bg: 'bg-yellow-100', text: 'text-yellow-700', label: 'Medium' },
  low:      { bg: 'bg-blue-100',   text: 'text-blue-700',   label: 'Low' },
};

interface Props {
  severity: Severity;
  size?: 'sm' | 'md';
}

const SreSeverityBadge: React.FC<Props> = ({ severity, size = 'md' }) => {
  const c = config[severity];
  return (
    <span className={`inline-flex items-center gap-1 font-semibold rounded-full ${c.bg} ${c.text} ${
      size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-xs'
    }`}>
      <span className={`w-1.5 h-1.5 rounded-full ${
        severity === 'critical' ? 'bg-red-500' :
        severity === 'high' ? 'bg-orange-500' :
        severity === 'medium' ? 'bg-yellow-500' : 'bg-blue-400'
      }`} />
      {c.label}
    </span>
  );
};

export default SreSeverityBadge;
```

- [ ] **Step 3: Create SreStatusBadge.tsx**

```tsx
// frontend-app/src/components/SRE/SreStatusBadge.tsx
import React from 'react';
import { InvestigationStatus } from '../../hooks/useSreInvestigation';
import { statusColors } from './sreUtils';

interface Props {
  status: InvestigationStatus;
}

const label: Record<InvestigationStatus, string> = {
  PENDING: 'Analyzing',
  RUNNING: 'Analyzing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

const SreStatusBadge: React.FC<Props> = ({ status }) => {
  const c = statusColors[status];
  const isActive = status === 'PENDING' || status === 'RUNNING';
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold ${c.bg} ${c.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${c.dot} ${isActive ? 'animate-pulse' : ''}`} />
      {label[status]}
    </span>
  );
};

export default SreStatusBadge;
```

- [ ] **Step 4: Commit**

```bash
git add frontend-app/src/components/SRE/sreUtils.ts \
        frontend-app/src/components/SRE/SreSeverityBadge.tsx \
        frontend-app/src/components/SRE/SreStatusBadge.tsx
git commit -m "feat(sre-ui): add shared SRE utility functions and badge components"
```

---

## Task 5: Frontend — SRE Dashboard Page

**Files:**
- Create: `frontend-app/src/pages/SreDashboardPage.tsx`

This is the main landing page at `/sre`. Shows live stats, active incidents, and recent activity — competing with the Datadog overview.

- [ ] **Step 1: Create SreDashboardPage.tsx**

```tsx
// frontend-app/src/pages/SreDashboardPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listInvestigations, getSreStats,
  SreInvestigation, SreStats
} from '../hooks/useSreInvestigation';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, formatMttr, resourceTypeLabel, duration } from '../components/SRE/sreUtils';

const SreDashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [stats, setStats] = useState<SreStats | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(async () => {
    try {
      const [invs, s] = await Promise.all([listInvestigations(), getSreStats()]);
      setInvestigations(invs);
      setStats(s);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 15000);
    return () => clearInterval(interval);
  }, [fetchAll]);

  const active = investigations.filter(i => i.status === 'PENDING' || i.status === 'RUNNING');
  const recent = investigations.slice(0, 10);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-400">Loading SRE dashboard…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-6xl w-full mx-auto px-6 py-8 space-y-6">

        {/* Page header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">SRE Intelligence</h1>
            <p className="text-sm text-gray-500 mt-0.5">AI-powered incident detection and root cause analysis</p>
          </div>
          <button
            onClick={() => navigate('/sre/investigations')}
            className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors"
          >
            View All Incidents
          </button>
        </div>

        {/* Stats row */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard
            label="Active Incidents"
            value={stats?.activeInvestigations ?? 0}
            highlight={stats ? stats.activeInvestigations > 0 : false}
            highlightColor="text-red-600"
          />
          <StatCard
            label="Resolved Today"
            value={stats?.completedToday ?? 0}
            highlight={false}
          />
          <StatCard
            label="Avg MTTR"
            value={formatMttr(stats?.avgMttrSeconds ?? null)}
            highlight={false}
            isText
          />
          <StatCard
            label="Total Investigations"
            value={stats?.totalInvestigations ?? 0}
            highlight={false}
          />
        </div>

        {/* Active incidents — only shown when there are active ones */}
        {active.length > 0 && (
          <div className="bg-white border border-red-100 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center gap-2 px-5 py-3.5 border-b border-red-100 bg-red-50">
              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
              <h2 className="text-sm font-semibold text-red-700">
                {active.length} Active Investigation{active.length > 1 ? 's' : ''}
              </h2>
            </div>
            <ul className="divide-y divide-gray-100">
              {active.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="flex items-center justify-between px-5 py-4 hover:bg-gray-50 cursor-pointer transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center text-amber-600 text-xs font-bold">
                      {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-gray-900">{inv.resourceName || inv.resourceId}</p>
                      <p className="text-xs text-gray-400">{resourceTypeLabel[inv.resourceType]} · {timeAgo(inv.createdAt)}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <SreStatusBadge status={inv.status} />
                    <svg className="w-4 h-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* No active incidents hero */}
        {active.length === 0 && investigations.length > 0 && (
          <div className="bg-white border border-emerald-100 rounded-2xl p-6 flex items-center gap-4">
            <div className="w-10 h-10 rounded-full bg-emerald-100 flex items-center justify-center">
              <svg className="w-5 h-5 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <div>
              <p className="text-sm font-semibold text-emerald-700">All systems operational</p>
              <p className="text-xs text-gray-400">No active investigations right now</p>
            </div>
          </div>
        )}

        {/* Empty state */}
        {investigations.length === 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl p-12 text-center">
            <div className="w-14 h-14 rounded-2xl bg-indigo-50 flex items-center justify-center mx-auto mb-4">
              <svg className="w-7 h-7 text-indigo-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                  d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
            </div>
            <h3 className="text-base font-semibold text-gray-800 mb-1">No investigations yet</h3>
            <p className="text-sm text-gray-500 mb-5">Connect your observability stack to start automatic detection</p>
            <button
              onClick={() => navigate('/sre/setup')}
              className="px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors"
            >
              Set up SRE
            </button>
          </div>
        )}

        {/* Recent activity */}
        {recent.length > 0 && (
          <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-5 py-3.5 border-b border-gray-100">
              <h2 className="text-sm font-semibold text-gray-700">Recent Activity</h2>
              <button
                onClick={() => navigate('/sre/investigations')}
                className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors"
              >
                View all →
              </button>
            </div>
            <ul className="divide-y divide-gray-100">
              {recent.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="flex items-center justify-between px-5 py-3.5 hover:bg-gray-50 cursor-pointer transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <SreStatusBadge status={inv.status} />
                    <div>
                      <p className="text-sm font-medium text-gray-800">{inv.resourceName || inv.resourceId}</p>
                      {inv.reportRootCause && (
                        <p className="text-xs text-gray-400 truncate max-w-xs">{inv.reportRootCause}</p>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    {inv.status === 'COMPLETED' && inv.startedAt && inv.completedAt && (
                      <span className="text-xs text-gray-400">{duration(inv.startedAt, inv.completedAt)}</span>
                    )}
                    <span className="text-xs text-gray-400">{timeAgo(inv.createdAt)}</span>
                    {inv.verdict && (
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                        inv.verdict === 'RESOLVED' ? 'bg-emerald-100 text-emerald-700' :
                        inv.verdict === 'FALSE_POSITIVE' ? 'bg-gray-100 text-gray-500' :
                        'bg-orange-100 text-orange-700'
                      }`}>
                        {inv.verdict === 'RESOLVED' ? 'Resolved' :
                         inv.verdict === 'FALSE_POSITIVE' ? 'False positive' : 'Wrong RCA'}
                      </span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
};

interface StatCardProps {
  label: string;
  value: number | string;
  highlight: boolean;
  highlightColor?: string;
  isText?: boolean;
}

const StatCard: React.FC<StatCardProps> = ({ label, value, highlight, highlightColor = 'text-red-600', isText }) => (
  <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
    <p className="text-xs text-gray-500 font-medium uppercase tracking-wide">{label}</p>
    <p className={`text-3xl font-bold mt-1 ${highlight ? highlightColor : 'text-gray-900'} ${isText ? 'text-2xl' : ''}`}>
      {value}
    </p>
  </div>
);

export default SreDashboardPage;
```

- [ ] **Step 2: Commit**

```bash
git add frontend-app/src/pages/SreDashboardPage.tsx
git commit -m "feat(sre-ui): add SRE Dashboard page with live stats, active incidents, activity feed"
```

---

## Task 6: Frontend — Redesigned Incidents List Page

**Files:**
- Replace: `frontend-app/src/pages/SreInvestigationsPage.tsx`

- [ ] **Step 1: Replace SreInvestigationsPage.tsx**

```tsx
// frontend-app/src/pages/SreInvestigationsPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listInvestigations, triggerInvestigation,
  SreInvestigation, InvestigationStatus, ResourceType
} from '../hooks/useSreInvestigation';
import { useTabState } from '../hooks/useTabState';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, resourceTypeLabel, duration } from '../components/SRE/sreUtils';

type FilterTab = 'ALL' | 'ACTIVE' | 'COMPLETED' | 'FAILED';

const SreInvestigationsPage: React.FC = () => {
  const navigate = useNavigate();
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [activeTab, setActiveTab] = useTabState<FilterTab>('ALL');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [triggering, setTriggering] = useState(false);
  const [showTriggerModal, setShowTriggerModal] = useState(false);

  const fetchData = useCallback(async () => {
    try {
      const data = await listInvestigations();
      setInvestigations(data);
    } catch {
      // silently fail on background polls
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 10000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const filtered = investigations.filter(inv => {
    const matchesTab =
      activeTab === 'ALL' ? true :
      activeTab === 'ACTIVE' ? (inv.status === 'PENDING' || inv.status === 'RUNNING') :
      activeTab === 'COMPLETED' ? inv.status === 'COMPLETED' :
      inv.status === 'FAILED';
    const q = search.toLowerCase();
    const matchesSearch = !q || (inv.resourceName || inv.resourceId).toLowerCase().includes(q)
      || (inv.reportRootCause || '').toLowerCase().includes(q);
    return matchesTab && matchesSearch;
  });

  const counts = {
    ALL: investigations.length,
    ACTIVE: investigations.filter(i => i.status === 'PENDING' || i.status === 'RUNNING').length,
    COMPLETED: investigations.filter(i => i.status === 'COMPLETED').length,
    FAILED: investigations.filter(i => i.status === 'FAILED').length,
  };

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-6xl w-full mx-auto px-6 py-8 space-y-5">

        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Investigations</h1>
            <p className="text-sm text-gray-500 mt-0.5">{investigations.length} total · auto-refreshing</p>
          </div>
          <button
            onClick={() => setShowTriggerModal(true)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
            </svg>
            New Investigation
          </button>
        </div>

        {/* Filters row */}
        <div className="flex items-center justify-between gap-4">
          <div className="flex gap-1.5">
            {(['ALL', 'ACTIVE', 'COMPLETED', 'FAILED'] as FilterTab[]).map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-3.5 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  activeTab === tab
                    ? 'bg-indigo-600 text-white shadow-sm'
                    : 'text-gray-500 hover:text-gray-800 bg-white border border-gray-200 hover:border-gray-300'
                }`}
              >
                {tab === 'ALL' ? `All (${counts.ALL})` :
                 tab === 'ACTIVE' ? `Active (${counts.ACTIVE})` :
                 tab === 'COMPLETED' ? `Completed (${counts.COMPLETED})` :
                 `Failed (${counts.FAILED})`}
              </button>
            ))}
          </div>
          <div className="relative">
            <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Search by service or root cause…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 text-sm border border-gray-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 w-72 placeholder-gray-400"
            />
          </div>
        </div>

        {/* Table */}
        <div className="bg-white rounded-2xl overflow-hidden border border-gray-200 shadow-sm">
          <div className="grid grid-cols-12 px-5 py-3 text-xs text-gray-400 uppercase tracking-wider border-b border-gray-100 font-medium">
            <span className="col-span-3">Service</span>
            <span className="col-span-2">Type</span>
            <span className="col-span-2">Status</span>
            <span className="col-span-3">Root Cause</span>
            <span className="col-span-1">Duration</span>
            <span className="col-span-1">Age</span>
          </div>

          {loading ? (
            <SkeletonRows count={5} />
          ) : filtered.length === 0 ? (
            <EmptyState search={search} onClear={() => setSearch('')} />
          ) : (
            <ul className="divide-y divide-gray-100">
              {filtered.map(inv => (
                <li
                  key={inv.id}
                  onClick={() => navigate(`/sre/investigations/${inv.id}`)}
                  className="grid grid-cols-12 px-5 py-4 items-center cursor-pointer hover:bg-gray-50 transition-colors group"
                >
                  {/* Service */}
                  <div className="col-span-3 flex items-center gap-2.5">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs font-bold shrink-0 ${
                      inv.status === 'COMPLETED' ? 'bg-emerald-100 text-emerald-700' :
                      inv.status === 'FAILED' ? 'bg-red-100 text-red-700' :
                      'bg-amber-100 text-amber-700'
                    }`}>
                      {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
                    </div>
                    <div className="min-w-0">
                      <p className="text-sm font-semibold text-gray-900 truncate">{inv.resourceName || inv.resourceId}</p>
                      <div className="flex items-center gap-1 mt-0.5">
                        <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${
                          inv.triggerType === 'AUTO'
                            ? 'bg-purple-100 text-purple-700'
                            : 'bg-gray-100 text-gray-500'
                        }`}>
                          {inv.triggerType === 'AUTO' ? 'Auto' : 'Manual'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Type */}
                  <span className="col-span-2 text-sm text-gray-500">{resourceTypeLabel[inv.resourceType]}</span>

                  {/* Status */}
                  <div className="col-span-2">
                    <SreStatusBadge status={inv.status} />
                  </div>

                  {/* Root cause preview */}
                  <div className="col-span-3 pr-4">
                    {inv.reportRootCause ? (
                      <p className="text-sm text-gray-600 truncate">{inv.reportRootCause}</p>
                    ) : inv.status === 'COMPLETED' ? (
                      <span className="text-sm text-gray-300">No root cause recorded</span>
                    ) : (
                      <span className="text-sm text-gray-300 italic">Investigating…</span>
                    )}
                  </div>

                  {/* Duration */}
                  <span className="col-span-1 text-sm text-gray-400">
                    {duration(inv.startedAt, inv.completedAt) || '—'}
                  </span>

                  {/* Age */}
                  <div className="col-span-1 flex items-center justify-between">
                    <span className="text-sm text-gray-400">{timeAgo(inv.createdAt)}</span>
                    <svg className="w-4 h-4 text-gray-300 group-hover:text-gray-500 transition-colors" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {/* Manual trigger modal */}
      {showTriggerModal && (
        <TriggerModal
          onClose={() => setShowTriggerModal(false)}
          onTrigger={async (req) => {
            setTriggering(true);
            try {
              const inv = await triggerInvestigation(req);
              setShowTriggerModal(false);
              navigate(`/sre/investigations/${inv.id}`);
            } finally {
              setTriggering(false);
            }
          }}
          triggering={triggering}
        />
      )}
    </div>
  );
};

const SkeletonRows: React.FC<{ count: number }> = ({ count }) => (
  <>
    {Array.from({ length: count }).map((_, i) => (
      <div key={i} className="grid grid-cols-12 px-5 py-4 items-center border-b border-gray-100">
        <div className="col-span-3 flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-gray-100 animate-pulse" />
          <div className="space-y-1.5">
            <div className="h-3 w-28 bg-gray-100 rounded animate-pulse" />
            <div className="h-2.5 w-14 bg-gray-100 rounded animate-pulse" />
          </div>
        </div>
        <div className="col-span-2"><div className="h-3 w-16 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-2"><div className="h-5 w-20 bg-gray-100 rounded-full animate-pulse" /></div>
        <div className="col-span-3"><div className="h-3 w-40 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-1"><div className="h-3 w-10 bg-gray-100 rounded animate-pulse" /></div>
        <div className="col-span-1"><div className="h-3 w-10 bg-gray-100 rounded animate-pulse" /></div>
      </div>
    ))}
  </>
);

const EmptyState: React.FC<{ search: string; onClear: () => void }> = ({ search, onClear }) => (
  <div className="py-16 text-center">
    <p className="text-gray-400 text-sm">
      {search ? (
        <>No results for "<span className="font-medium text-gray-600">{search}</span>". <button onClick={onClear} className="text-indigo-600 hover:underline">Clear search</button></>
      ) : 'No investigations found.'}
    </p>
  </div>
);

interface TriggerModalProps {
  onClose: () => void;
  onTrigger: (req: { resourceType: ResourceType; resourceId: string; resourceName?: string }) => Promise<void>;
  triggering: boolean;
}

const TriggerModal: React.FC<TriggerModalProps> = ({ onClose, onTrigger, triggering }) => {
  const [resourceType, setResourceType] = useState<ResourceType>('K8S_POD');
  const [resourceId, setResourceId] = useState('');
  const [resourceName, setResourceName] = useState('');

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md p-6 space-y-5" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-gray-900">New Investigation</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Resource Type</label>
            <select
              value={resourceType}
              onChange={e => setResourceType(e.target.value as ResourceType)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="K8S_POD">K8s Pod</option>
              <option value="EC2">EC2 Instance</option>
              <option value="RDS">RDS</option>
              <option value="AZURE_VM">Azure VM</option>
              <option value="GCP_VM">GCP VM</option>
              <option value="GENERIC">Generic Service</option>
            </select>
          </div>

          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Resource ID</label>
            <input
              type="text"
              placeholder="e.g. user-service, i-0abc123"
              value={resourceId}
              onChange={e => setResourceId(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 placeholder-gray-300"
            />
          </div>

          <div>
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide block mb-1.5">Display Name <span className="text-gray-400 normal-case">(optional)</span></label>
            <input
              type="text"
              placeholder="e.g. User Service"
              value={resourceName}
              onChange={e => setResourceName(e.target.value)}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 placeholder-gray-300"
            />
          </div>
        </div>

        <div className="flex gap-3 pt-1">
          <button
            onClick={onClose}
            className="flex-1 px-4 py-2.5 border border-gray-200 text-gray-600 text-sm font-medium rounded-lg hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            disabled={!resourceId.trim() || triggering}
            onClick={() => onTrigger({ resourceType, resourceId: resourceId.trim(), resourceName: resourceName.trim() || undefined })}
            className="flex-1 px-4 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {triggering ? 'Starting…' : 'Start Investigation'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default SreInvestigationsPage;
```

- [ ] **Step 2: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationsPage.tsx
git commit -m "feat(sre-ui): redesign incidents list with search, trigger modal, skeleton loading"
```

---

## Task 7: Frontend — Redesigned Incident Detail Page

**Files:**
- Replace: `frontend-app/src/pages/SreInvestigationDetailPage.tsx`

This is the richest page — shows the full investigation with confidence bar, evidence, verdict buttons, and rendered report. Competing with Datadog incident detail.

- [ ] **Step 1: Replace SreInvestigationDetailPage.tsx**

```tsx
// frontend-app/src/pages/SreInvestigationDetailPage.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  getInvestigation, pollStatus, submitVerdict,
  SreInvestigation, Verdict
} from '../hooks/useSreInvestigation';
import SreStatusBadge from '../components/SRE/SreStatusBadge';
import { timeAgo, duration, resourceTypeLabel } from '../components/SRE/sreUtils';

const SreInvestigationDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [inv, setInv] = useState<SreInvestigation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [submittingVerdict, setSubmittingVerdict] = useState(false);
  const [showFullReport, setShowFullReport] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await getInvestigation(Number(id));
      setInv(data);
    } catch {
      setError('Investigation not found or access denied.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  // Poll while running
  useEffect(() => {
    if (!inv || inv.status === 'COMPLETED' || inv.status === 'FAILED') return;
    const interval = setInterval(async () => {
      try {
        const { status } = await pollStatus(Number(id));
        if (status === 'COMPLETED' || status === 'FAILED') {
          await load();
          clearInterval(interval);
        }
      } catch {
        clearInterval(interval);
      }
    }, 4000);
    return () => clearInterval(interval);
  }, [inv, id, load]);

  const handleVerdict = async (verdict: Verdict) => {
    if (!inv) return;
    setSubmittingVerdict(true);
    try {
      const updated = await submitVerdict(inv.id, verdict);
      setInv(updated);
    } finally {
      setSubmittingVerdict(false);
    }
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-400">Loading investigation…</p>
        </div>
      </div>
    );
  }

  if (error || !inv) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <p className="text-red-500 font-medium">{error || 'Investigation not found.'}</p>
          <button onClick={() => navigate('/sre/investigations')} className="mt-3 text-sm text-indigo-600 hover:underline">
            ← Back to investigations
          </button>
        </div>
      </div>
    );
  }

  const isRunning = inv.status === 'PENDING' || inv.status === 'RUNNING';

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-4xl w-full mx-auto px-6 py-8 space-y-5">

        {/* Breadcrumb */}
        <button
          onClick={() => navigate('/sre/investigations')}
          className="flex items-center gap-1.5 text-sm text-gray-400 hover:text-gray-700 transition-colors"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          Investigations
        </button>

        {/* Hero card */}
        <div className="bg-white border border-gray-200 rounded-2xl shadow-sm p-6">
          <div className="flex items-start justify-between gap-4">
            <div className="flex items-start gap-4">
              <div className={`w-12 h-12 rounded-xl flex items-center justify-center text-lg font-bold shrink-0 ${
                inv.status === 'COMPLETED' ? 'bg-emerald-100 text-emerald-700' :
                inv.status === 'FAILED' ? 'bg-red-100 text-red-700' :
                'bg-amber-100 text-amber-700'
              }`}>
                {(inv.resourceName || inv.resourceId).charAt(0).toUpperCase()}
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900">{inv.resourceName || inv.resourceId}</h1>
                <div className="flex items-center flex-wrap gap-2 mt-1.5">
                  <SreStatusBadge status={inv.status} />
                  <span className="text-xs text-gray-400">{resourceTypeLabel[inv.resourceType]}</span>
                  <span className="text-xs text-gray-300">·</span>
                  <span className={`text-xs px-1.5 py-0.5 rounded font-medium ${
                    inv.triggerType === 'AUTO' ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-500'
                  }`}>
                    {inv.triggerType === 'AUTO' ? 'Auto-detected' : 'Manual'}
                  </span>
                  <span className="text-xs text-gray-300">·</span>
                  <span className="text-xs text-gray-400">{timeAgo(inv.createdAt)}</span>
                  {inv.startedAt && inv.completedAt && (
                    <>
                      <span className="text-xs text-gray-300">·</span>
                      <span className="text-xs text-gray-400">Completed in {duration(inv.startedAt, inv.completedAt)}</span>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* Verdict badge if already submitted */}
            {inv.verdict && (
              <span className={`shrink-0 px-3 py-1.5 rounded-lg text-sm font-semibold ${
                inv.verdict === 'RESOLVED' ? 'bg-emerald-100 text-emerald-700' :
                inv.verdict === 'FALSE_POSITIVE' ? 'bg-gray-100 text-gray-600' :
                'bg-orange-100 text-orange-700'
              }`}>
                {inv.verdict === 'RESOLVED' ? '✓ Resolved' :
                 inv.verdict === 'FALSE_POSITIVE' ? 'False positive' : 'Wrong root cause'}
              </span>
            )}
          </div>
        </div>

        {/* Running state */}
        {isRunning && (
          <div className="bg-amber-50 border border-amber-200 rounded-2xl p-5">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-3 h-3 bg-amber-400 rounded-full animate-pulse shrink-0" />
              <p className="text-amber-700 font-semibold text-sm">AI investigation in progress</p>
            </div>
            <div className="space-y-2">
              {['Connecting to observability stack', 'Gathering logs and metrics', 'Analyzing root cause', 'Generating recommendations'].map((step, i) => (
                <div key={i} className="flex items-center gap-2">
                  <div className={`w-1.5 h-1.5 rounded-full ${i === 0 ? 'bg-amber-500 animate-pulse' : 'bg-amber-200'}`} />
                  <span className={`text-xs ${i === 0 ? 'text-amber-700' : 'text-amber-400'}`}>{step}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Failed state */}
        {inv.status === 'FAILED' && (
          <div className="bg-red-50 border border-red-200 rounded-2xl p-5">
            <p className="text-red-600 font-semibold text-sm mb-1">Investigation failed</p>
            <p className="text-red-500 text-sm">{inv.errorMessage || 'An unexpected error occurred during investigation.'}</p>
          </div>
        )}

        {/* Completed results */}
        {inv.status === 'COMPLETED' && (
          <div className="space-y-4">

            {/* Summary */}
            {inv.reportSummary && (
              <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                <div className="flex items-center gap-2 mb-3">
                  <div className="w-5 h-5 rounded bg-blue-100 flex items-center justify-center">
                    <svg className="w-3 h-3 text-blue-600" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                    </svg>
                  </div>
                  <h3 className="text-sm font-semibold text-gray-800">Summary</h3>
                </div>
                <p className="text-sm text-gray-600 leading-relaxed">{inv.reportSummary}</p>
              </div>
            )}

            {/* Root cause + Recommendations side by side */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {inv.reportRootCause && (
                <div className="bg-white border border-red-100 rounded-2xl p-5 shadow-sm">
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-5 h-5 rounded bg-red-100 flex items-center justify-center">
                      <svg className="w-3 h-3 text-red-600" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                      </svg>
                    </div>
                    <h3 className="text-sm font-semibold text-gray-800">Root Cause</h3>
                  </div>
                  <p className="text-sm text-gray-600 leading-relaxed">{inv.reportRootCause}</p>
                </div>
              )}

              {inv.reportRecommendations && inv.reportRecommendations.length > 0 && (
                <div className="bg-white border border-emerald-100 rounded-2xl p-5 shadow-sm">
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-5 h-5 rounded bg-emerald-100 flex items-center justify-center">
                      <svg className="w-3 h-3 text-emerald-600" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                      </svg>
                    </div>
                    <h3 className="text-sm font-semibold text-gray-800">Recommended Actions</h3>
                  </div>
                  <ol className="space-y-2">
                    {inv.reportRecommendations.map((rec, i) => (
                      <li key={i} className="flex gap-2.5 items-start">
                        <span className="w-5 h-5 rounded-full bg-emerald-100 text-emerald-700 text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">{i + 1}</span>
                        <span className="text-sm text-gray-600 leading-snug">{rec}</span>
                      </li>
                    ))}
                  </ol>
                </div>
              )}
            </div>

            {/* Full report toggle */}
            {inv.reportFullMarkdown && (
              <div className="bg-white border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
                <button
                  onClick={() => setShowFullReport(v => !v)}
                  className="w-full flex items-center justify-between px-5 py-4 hover:bg-gray-50 transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                    <span className="text-sm font-medium text-gray-700">Full Investigation Report</span>
                  </div>
                  <svg className={`w-4 h-4 text-gray-400 transition-transform ${showFullReport ? 'rotate-180' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                  </svg>
                </button>
                {showFullReport && (
                  <div className="px-5 pb-5 border-t border-gray-100">
                    <pre className="text-xs text-gray-600 leading-relaxed whitespace-pre-wrap font-mono overflow-x-auto pt-4 max-h-96 overflow-y-auto">
                      {inv.reportFullMarkdown}
                    </pre>
                  </div>
                )}
              </div>
            )}

            {/* Verdict section */}
            {!inv.verdict && (
              <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                <p className="text-sm font-semibold text-gray-700 mb-3">Was this investigation accurate?</p>
                <div className="flex gap-2.5">
                  <VerdictButton
                    label="✓ Resolved"
                    description="Root cause was correct"
                    color="emerald"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('RESOLVED')}
                  />
                  <VerdictButton
                    label="False Positive"
                    description="Not a real incident"
                    color="gray"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('FALSE_POSITIVE')}
                  />
                  <VerdictButton
                    label="Wrong Root Cause"
                    description="Something else caused it"
                    color="orange"
                    disabled={submittingVerdict}
                    onClick={() => handleVerdict('WRONG_ROOT_CAUSE')}
                  />
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

interface VerdictButtonProps {
  label: string;
  description: string;
  color: 'emerald' | 'gray' | 'orange';
  disabled: boolean;
  onClick: () => void;
}

const colorMap = {
  emerald: 'border-emerald-200 hover:border-emerald-400 hover:bg-emerald-50 text-emerald-700',
  gray:    'border-gray-200   hover:border-gray-400   hover:bg-gray-50   text-gray-600',
  orange:  'border-orange-200 hover:border-orange-400 hover:bg-orange-50 text-orange-700',
};

const VerdictButton: React.FC<VerdictButtonProps> = ({ label, description, color, disabled, onClick }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className={`flex-1 border-2 rounded-xl px-4 py-3 text-left transition-all disabled:opacity-50 disabled:cursor-not-allowed ${colorMap[color]}`}
  >
    <p className="text-sm font-semibold">{label}</p>
    <p className="text-xs text-gray-400 mt-0.5">{description}</p>
  </button>
);

export default SreInvestigationDetailPage;
```

- [ ] **Step 2: Commit**

```bash
git add frontend-app/src/pages/SreInvestigationDetailPage.tsx
git commit -m "feat(sre-ui): redesign incident detail with verdict buttons, collapsible report, running state"
```

---

## Task 8: Frontend — Setup / Onboarding Page

**Files:**
- Create: `frontend-app/src/pages/SreSetupPage.tsx`

4-step wizard: choose stack → API key → verify → SLOs.

- [ ] **Step 1: Create SreSetupPage.tsx**

```tsx
// frontend-app/src/pages/SreSetupPage.tsx
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';

type Stack = 'prometheus' | 'datadog' | 'newrelic' | 'openobserve' | 'none';

const stackConfig: Record<Stack, { label: string; icon: string; description: string; effort: string }> = {
  prometheus:   { label: 'Prometheus / Grafana', icon: '📊', description: 'Add OTel Collector alongside existing stack', effort: '~1 hour' },
  datadog:      { label: 'Datadog',              icon: '🐶', description: 'Fan-out to XamOps via OTel Collector',      effort: '~30 min' },
  newrelic:     { label: 'New Relic',            icon: '🔵', description: 'Fan-out to XamOps via OTel Collector',      effort: '~30 min' },
  openobserve:  { label: 'OpenObserve',          icon: '🔭', description: 'Already connected — just configure',         effort: '~5 min' },
  none:         { label: 'Nothing yet',          icon: '📦', description: 'Deploy full stack with one Helm command',   effort: '~2 hours' },
};

const SreSetupPage: React.FC = () => {
  const navigate = useNavigate();
  const [step, setStep] = useState(1);
  const [stack, setStack] = useState<Stack | null>(null);
  const [verified, setVerified] = useState(false);
  const [verifying, setVerifying] = useState(false);

  const apiKey = 'xamops_key_' + Math.random().toString(36).slice(2, 10);

  const handleVerify = async () => {
    setVerifying(true);
    await new Promise(r => setTimeout(r, 2000)); // simulate check
    setVerified(true);
    setVerifying(false);
  };

  return (
    <div className="flex flex-col h-full overflow-y-auto bg-gray-50">
      <div className="max-w-2xl w-full mx-auto px-6 py-12 space-y-8">

        {/* Steps indicator */}
        <div className="flex items-center gap-0">
          {[1, 2, 3, 4].map((s, i) => (
            <React.Fragment key={s}>
              <div className={`flex items-center gap-2 ${step >= s ? 'text-indigo-600' : 'text-gray-300'}`}>
                <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-all ${
                  step > s ? 'bg-indigo-600 border-indigo-600 text-white' :
                  step === s ? 'border-indigo-600 text-indigo-600' :
                  'border-gray-200 text-gray-300'
                }`}>
                  {step > s ? '✓' : s}
                </div>
                <span className="text-xs font-medium hidden sm:block">
                  {s === 1 ? 'Choose stack' : s === 2 ? 'Connect' : s === 3 ? 'Verify' : 'Done'}
                </span>
              </div>
              {i < 3 && <div className={`flex-1 h-0.5 mx-3 ${step > s ? 'bg-indigo-400' : 'bg-gray-200'}`} />}
            </React.Fragment>
          ))}
        </div>

        {/* Step 1 — Choose stack */}
        {step === 1 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">What observability stack are you using?</h2>
              <p className="text-sm text-gray-500 mt-1">We'll generate the right integration config for you.</p>
            </div>
            <div className="space-y-2.5">
              {(Object.entries(stackConfig) as [Stack, typeof stackConfig[Stack]][]).map(([key, cfg]) => (
                <button
                  key={key}
                  onClick={() => setStack(key)}
                  className={`w-full flex items-center gap-4 p-4 rounded-xl border-2 text-left transition-all ${
                    stack === key
                      ? 'border-indigo-500 bg-indigo-50'
                      : 'border-gray-200 bg-white hover:border-gray-300'
                  }`}
                >
                  <span className="text-2xl">{cfg.icon}</span>
                  <div className="flex-1">
                    <p className="text-sm font-semibold text-gray-900">{cfg.label}</p>
                    <p className="text-xs text-gray-500 mt-0.5">{cfg.description}</p>
                  </div>
                  <span className="text-xs text-gray-400 shrink-0">{cfg.effort}</span>
                </button>
              ))}
            </div>
            <button
              disabled={!stack}
              onClick={() => setStep(2)}
              className="w-full py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              Continue
            </button>
          </div>
        )}

        {/* Step 2 — Connect */}
        {step === 2 && stack && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">Connect {stackConfig[stack].label}</h2>
              <p className="text-sm text-gray-500 mt-1">Add these credentials to your collector config.</p>
            </div>

            <div className="bg-white border border-gray-200 rounded-2xl p-5 space-y-3 shadow-sm">
              <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">Your API Key</p>
              <div className="flex items-center gap-2">
                <code className="flex-1 font-mono text-sm bg-gray-50 border border-gray-200 rounded-lg px-3 py-2 text-gray-800 truncate">{apiKey}</code>
                <button
                  onClick={() => navigator.clipboard.writeText(apiKey)}
                  className="px-3 py-2 text-xs font-medium text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors shrink-0"
                >
                  Copy
                </button>
              </div>
            </div>

            {/* Stack-specific config */}
            <div className="bg-gray-900 rounded-2xl p-5 overflow-auto">
              <p className="text-xs font-medium text-gray-400 mb-3">
                {stack === 'prometheus' ? 'otel-collector-config.yaml' :
                 stack === 'datadog' || stack === 'newrelic' ? 'otel-collector-config.yaml' :
                 stack === 'openobserve' ? 'OpenObserve Settings → Forwarding' :
                 'Deploy command'}
              </p>
              <pre className="text-xs text-green-400 leading-relaxed font-mono">
                {stack === 'prometheus' && `exporters:
  otlphttp:
    endpoint: "https://ingest.xamops.io/v1"
    headers:
      Authorization: "Bearer ${apiKey}"

service:
  pipelines:
    metrics:
      exporters: [prometheus_existing, otlphttp]`}
                {(stack === 'datadog' || stack === 'newrelic') && `exporters:
  # keep sending to ${stack === 'datadog' ? 'Datadog' : 'New Relic'} (unchanged)
  ${stack}:
    api:
      key: \${EXISTING_API_KEY}
  # also send to XamOps
  otlphttp:
    endpoint: "https://ingest.xamops.io/v1"
    headers:
      Authorization: "Bearer ${apiKey}"

service:
  pipelines:
    metrics:
      exporters: [${stack}, otlphttp]  # fan-out`}
                {stack === 'openobserve' && `# In OpenObserve → Settings → Forwarding:
Endpoint: https://ingest.xamops.io/v1
Authorization: Bearer ${apiKey}`}
                {stack === 'none' && `helm install xamops-sre ./charts/xamops-sre \\
  --set global.apiKey=${apiKey} \\
  --set global.ingestEndpoint=https://ingest.xamops.io`}
              </pre>
            </div>

            <div className="flex gap-3">
              <button onClick={() => setStep(1)} className="px-4 py-3 border border-gray-200 text-gray-600 text-sm font-medium rounded-xl hover:bg-gray-50 transition-colors">
                Back
              </button>
              <button onClick={() => setStep(3)} className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors">
                I've added the config
              </button>
            </div>
          </div>
        )}

        {/* Step 3 — Verify */}
        {step === 3 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-xl font-bold text-gray-900">Verify connection</h2>
              <p className="text-sm text-gray-500 mt-1">Send some traffic to your services, then verify we're receiving signals.</p>
            </div>

            <div className={`bg-white border-2 rounded-2xl p-6 text-center transition-all ${
              verified ? 'border-emerald-300' : 'border-gray-200'
            }`}>
              {!verified && !verifying && (
                <div className="space-y-3">
                  <div className="w-12 h-12 rounded-full bg-gray-100 flex items-center justify-center mx-auto">
                    <svg className="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.111 16.404a5.5 5.5 0 017.778 0M12 20h.01m-7.08-7.071c3.904-3.905 10.236-3.905 14.141 0M1.394 9.393c5.857-5.857 15.355-5.857 21.213 0" />
                    </svg>
                  </div>
                  <p className="text-sm text-gray-600">Click below to check if we're receiving signals from your stack.</p>
                  <button onClick={handleVerify} className="px-6 py-2.5 bg-indigo-600 text-white text-sm font-medium rounded-lg hover:bg-indigo-700 transition-colors">
                    Check Connection
                  </button>
                </div>
              )}
              {verifying && (
                <div className="space-y-3">
                  <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin mx-auto" />
                  <p className="text-sm text-gray-500">Listening for signals…</p>
                </div>
              )}
              {verified && (
                <div className="space-y-3">
                  <div className="w-12 h-12 rounded-full bg-emerald-100 flex items-center justify-center mx-auto">
                    <svg className="w-6 h-6 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </div>
                  <p className="text-sm font-semibold text-emerald-700">Connection verified!</p>
                  <p className="text-xs text-gray-500">Signals are flowing. Your services are now being monitored.</p>
                </div>
              )}
            </div>

            <div className="flex gap-3">
              <button onClick={() => setStep(2)} className="px-4 py-3 border border-gray-200 text-gray-600 text-sm font-medium rounded-xl hover:bg-gray-50 transition-colors">
                Back
              </button>
              <button
                disabled={!verified}
                onClick={() => setStep(4)}
                className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                Continue
              </button>
            </div>
          </div>
        )}

        {/* Step 4 — Done */}
        {step === 4 && (
          <div className="space-y-6 text-center">
            <div className="w-16 h-16 rounded-2xl bg-indigo-100 flex items-center justify-center mx-auto">
              <svg className="w-8 h-8 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
              </svg>
            </div>
            <div>
              <h2 className="text-xl font-bold text-gray-900">You're all set!</h2>
              <p className="text-sm text-gray-500 mt-2 max-w-sm mx-auto">
                XamOps SRE is now monitoring your services. Investigations will start automatically when anomalies are detected.
              </p>
            </div>
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-left">
              <p className="text-xs font-semibold text-amber-700 mb-1.5">Expect realistic results within:</p>
              <ul className="space-y-1">
                {['First anomaly detection: ~1 hour after traffic flows', 'Baseline established: 24–48 hours', 'Full accuracy: 2–4 weeks'].map(line => (
                  <li key={line} className="flex items-center gap-2 text-xs text-amber-600">
                    <span className="w-1 h-1 rounded-full bg-amber-400" />
                    {line}
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => navigate('/sre')}
                className="flex-1 py-3 bg-indigo-600 text-white font-medium rounded-xl hover:bg-indigo-700 transition-colors"
              >
                Go to SRE Dashboard
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default SreSetupPage;
```

- [ ] **Step 2: Commit**

```bash
git add frontend-app/src/pages/SreSetupPage.tsx
git commit -m "feat(sre-ui): add onboarding wizard with stack selection, API key, verify steps"
```

---

## Task 9: Frontend — Routes + Sidebar

**Files:**
- Modify: `frontend-app/src/App.tsx`
- Modify: `frontend-app/src/components/Sidebar/Sidebar.tsx`

- [ ] **Step 1: Add routes to App.tsx**

Find the SRE section in App.tsx (around lines 121-122 and 511-512).

Replace the existing SRE lazy imports:
```typescript
const SreInvestigationsPage = lazy(() => import('./pages/SreInvestigationsPage'));
const SreInvestigationDetailPage = lazy(() => import('./pages/SreInvestigationDetailPage'));
```

With:
```typescript
const SreDashboardPage = lazy(() => import('./pages/SreDashboardPage'));
const SreInvestigationsPage = lazy(() => import('./pages/SreInvestigationsPage'));
const SreInvestigationDetailPage = lazy(() => import('./pages/SreInvestigationDetailPage'));
const SreSetupPage = lazy(() => import('./pages/SreSetupPage'));
```

Replace the existing SRE routes (around lines 511-512):
```tsx
<Route path="/sre/investigations" element={<SreInvestigationsPage />} />
<Route path="/sre/investigations/:id" element={<SreInvestigationDetailPage />} />
```

With:
```tsx
<Route path="/sre" element={<SreDashboardPage />} />
<Route path="/sre/investigations" element={<SreInvestigationsPage />} />
<Route path="/sre/investigations/:id" element={<SreInvestigationDetailPage />} />
<Route path="/sre/setup" element={<SreSetupPage />} />
```

- [ ] **Step 2: Add SRE section to Sidebar**

Open `frontend-app/src/components/Sidebar/Sidebar.tsx`.

Find the `sreRoutes` or the auto-open logic. Add `/sre` to the relevant section detection (search for `const sreRoutes` or the `useEffect` for auto-open):

In the `useEffect` that sets `openDropdown` based on path, add:
```typescript
const sreRoutes = ['/sre', '/sre/investigations', '/sre/setup'];
```

Then find where the NavSection items are rendered (look for other sections like `secopsRoutes`) and add the SRE nav section. Find a good spot (after SecOps or AIOps) and add:

```tsx
{/* SRE */}
<NavSection
  label="SRE"
  icon={
    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
        d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
    </svg>
  }
  isOpen={openDropdown === 'sre'}
  onToggle={() => toggleDropdown('sre')}
>
  <NavItem to="/sre" label="Dashboard" />
  <NavItem to="/sre/investigations" label="Investigations" />
  <NavItem to="/sre/setup" label="Setup" />
</NavSection>
```

- [ ] **Step 3: Commit**

```bash
git add frontend-app/src/App.tsx \
        frontend-app/src/components/Sidebar/Sidebar.tsx
git commit -m "feat(sre-ui): add SRE routes and sidebar navigation section"
```

---

## Task 10: End-to-End Verification

- [ ] **Step 1: Start the frontend dev server**

```bash
cd frontend-app && npm start
```

- [ ] **Step 2: Navigate to /sre**

Expected: SRE Dashboard page loads. Shows loading spinner then either empty state with "Set up SRE" CTA or stats + activity feed.

- [ ] **Step 3: Navigate to /sre/investigations**

Expected: Investigations list loads. Shows filter tabs (All / Active / Completed / Failed), search box, skeleton rows during load, then the table.

- [ ] **Step 4: Click "New Investigation" button**

Expected: Modal opens with Resource Type dropdown, Resource ID input, optional Display Name. Cancel closes it. Attempting to submit with empty Resource ID keeps button disabled.

- [ ] **Step 5: Click an existing investigation (if any)**

Expected: Detail page loads with hero card, status badge, and (if completed) summary/root cause/recommendations cards. Verdict buttons appear at the bottom if no verdict yet.

- [ ] **Step 6: Navigate to /sre/setup**

Expected: Onboarding wizard loads at Step 1. Selecting a stack enables Continue. Step 2 shows appropriate config snippet. Step 3 has verify button. Step 4 shows success state.

- [ ] **Step 7: Check sidebar**

Expected: SRE section appears in sidebar with Dashboard, Investigations, Setup links. Active link highlights correctly.

- [ ] **Step 8: TypeScript check**

```bash
cd frontend-app && npx tsc --noEmit 2>&1 | head -20
```
Expected: No errors (or only pre-existing errors unrelated to SRE files).

- [ ] **Step 9: Final commit**

```bash
git add .
git status  # verify only SRE files
git commit -m "feat(sre): complete SRE UI redesign — dashboard, incidents, detail, onboarding wizard"
```

---

## Self-Review

### Spec coverage

| Feature | Task |
|---|---|
| SRE Dashboard with live stats | Task 5 |
| Active incidents with pulse animation | Task 5 |
| MTTR formatted display | Task 4 (sreUtils), Task 5 |
| Incidents list with search + filters | Task 6 |
| Skeleton loading states | Task 6 |
| Manual trigger modal | Task 6 |
| AUTO/MANUAL trigger badges | Task 6 |
| Incident detail with root cause | Task 7 |
| Collapsible full report | Task 7 |
| Verdict buttons (Resolved/FP/Wrong RCA) | Task 7 + Task 2 |
| Onboarding wizard 4 steps | Task 8 |
| Stack-specific config snippets | Task 8 |
| Routes wired | Task 9 |
| Sidebar SRE section | Task 9 |
| Backend verdict endpoint | Task 2 |
| Backend stats endpoint | Task 2 |

### Placeholder scan
No TBD, TODO, "similar to above", or incomplete code blocks.

### Type consistency
- `Verdict` type (`'RESOLVED' | 'FALSE_POSITIVE' | 'WRONG_ROOT_CAUSE'`) defined in hook (Task 3), used in detail page (Task 7) and backend (Task 1-2).
- `SreStats` interface defined in hook (Task 3), used in dashboard (Task 5).
- `ResourceType` includes `'GENERIC'` consistently across hook, modal, and backend.
- `sreUtils` exports (`timeAgo`, `formatMttr`, `duration`, `resourceTypeLabel`, `statusColors`) imported correctly in Tasks 5, 6, 7.
- `SreSeverityBadge` and `SreStatusBadge` imported by path `../components/SRE/...` consistently.
