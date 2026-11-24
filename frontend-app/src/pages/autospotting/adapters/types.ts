import type React from 'react';
import type { GroupDetail, GroupRow, Plan, PlanBodyDoc } from '../../../api/autospottingApi';

export interface ColumnDef<T> {
  id: string;
  header: string;
  render: (row: T) => React.ReactNode;
}

export interface GuidanceLink {
  label: string;
  text: string;
  href?: string;
}

/**
 * Everything that differs between clouds (design-ux-ops.md §2.3). The shell is provider-neutral and
 * asks the adapter for these.
 */
export interface ProviderAdapter {
  provider: 'AWS' | 'GCP';
  /** Official asset path. */
  icon: string;
  /** 'Auto Scaling Group' | 'Managed Instance Group' */
  groupNoun: string;
  /** 'Region' | 'Location' */
  locationLabel: string;
  /** Extra provider columns, appended after the shared ones. */
  columns: ColumnDef<GroupRow>[];
  /** Read-only managed fields. `current` is the live config from a plan body when one is loaded. */
  ConfigFields: React.FC<{ group: GroupDetail; current?: Record<string, unknown> | null }>;
  /** The target block of a plan (FR-PLN-02). */
  PlanTargetFields: React.FC<{ plan: Plan; body: PlanBodyDoc }>;
  permissionGuidance: (code: string) => GuidanceLink;
}
