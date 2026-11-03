/**
 * Platform-wide XamOps page rules, managed from the BillOps admin User Manager.
 * Mirrors billops GlobalTabRuleDTO (/api/billops/admin/xamops-page-rules).
 */
import type { XamGlobalTabMode } from '../constants/xamopsTabs';

export type XamPageRuleMode = XamGlobalTabMode;

/** A user the rule does not apply to; their own tab access decides. */
export interface XamPageRuleExceptionUser {
  userId: number;
  username: string;
  email: string | null;
  clientName: string | null;
}

export interface XamPageRule {
  /** XamOps module key ("observability") or tab key ("obs-logs"). */
  tabKey: string;
  mode: XamPageRuleMode;
  updatedBy: string | null;
  updatedAt: string | null;
  exceptions: XamPageRuleExceptionUser[];
}

export interface XamPageRulesListResponse {
  /** False for admins limited to some cloud accounts: they can read the rules but not change them. */
  canEdit: boolean;
  rules: XamPageRule[];
}

export interface UseXamOpsPageRulesResult {
  /** Rules keyed by tabKey. */
  rules: Record<string, XamPageRule>;
  canEdit: boolean;
  loading: boolean;
  /** Load failure; the section shows it and the rest of the page keeps working. */
  error: string | null;
  /** The key with a save in flight, if any. */
  savingKey: string | null;
  /** Last save failure, until dismissed. */
  mutationError: string | null;
  refresh: () => Promise<void>;
  /** `null` clears the rule, putting the page back on per-user access. Resolves true on success. */
  setMode: (tabKey: string, mode: XamPageRuleMode | null) => Promise<boolean>;
  addException: (tabKey: string, userId: number) => Promise<boolean>;
  removeException: (tabKey: string, userId: number) => Promise<boolean>;
  clearMutationError: () => void;
}
