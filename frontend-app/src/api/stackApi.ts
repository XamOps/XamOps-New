import { apiFetch } from '../utils/apiClient';

/**
 * Which CloudFormation stack owns an account's connection, and — when none can be found —
 * why not.
 *
 * "Missing" is not one situation. A deleted stack, one that never finished creating, one in
 * another region, and a role that was never made by a stack at all each need a different
 * response; reporting them all as "not found" sends the reader down the wrong path most of the
 * time. The backend distinguishes them from the API responses alone.
 */

export type StackVerdict =
  /** Found and in a state that accepts an update. */
  | 'HEALTHY'
  /** Found, but mid-operation. Not broken — busy. */
  | 'BUSY'
  /** Found, but in a state AWS will not update. */
  | 'UNUPDATABLE'
  /** Its last update rolled back and the rollback failed. */
  | 'ROLLBACK_STUCK'
  /** The role works but carries no CloudFormation permission. */
  | 'NO_CFN_PERMISSION'
  /** The recorded stack is gone; its role may still be working. */
  | 'STACK_DELETED'
  /** A stack of the same name exists, but it is not the one we created. */
  | 'STACK_REPLACED'
  /** The role exists but no stack claims it. */
  | 'NOT_STACK_MANAGED'
  /** The role could not be assumed — a broken connection, not a stack problem. */
  | 'CONNECTION_BROKEN'
  | 'UNKNOWN';

export interface StackDiagnosis {
  verdict: StackVerdict;
  stackId: string | null;
  stackName: string | null;
  stackRegion: string | null;
  /** The CloudFormation status where one could be read, else null. */
  stackStatus: string | null;
  /** Whether an update could proceed today, permissions included. */
  updatable: boolean;
  /** A sentence naming the cause, written for the person reading the screen. */
  detail: string;
  /** The single next action, or null when none applies. */
  remedy: string | null;
}

/** Read-only: it answers whether an update could run, never runs one. */
export const fetchStackStatus = async (accountId: string): Promise<StackDiagnosis> => {
  const res = await apiFetch(
    `/api/xamops/account-manager/stack-status/${encodeURIComponent(accountId)}`,
  );
  if (!res.ok) {
    throw new Error((await res.json().catch(() => ({}))).error ?? `HTTP ${res.status}`);
  }
  return (await res.json()) as StackDiagnosis;
};

/** One resource a change set will touch. */
export interface ResourceChange {
  logicalId: string;
  resourceType: string;
  /** Add, Modify, Remove, Dynamic. */
  action: string;
  /** True, False, Conditional — only meaningful for Modify. */
  replacement: string | null;
}

/** One parameter the update would move, shown beside the resources it causes to change. */
export interface ParameterChange {
  name: string;
  from: string;
  to: string;
}

export interface StackUpdatePreview {
  /** Null when there is nothing to change. */
  changeSetId: string | null;
  stackName: string;
  stackId: string;
  region: string;
  /** True when the stack already matches — a success, not a failure. */
  noChanges: boolean;
  parameterChanges: ParameterChange[];
  resourceChanges: ResourceChange[];
  detail: string | null;
}

export interface StackEventLine {
  timestamp: string;
  logicalId: string;
  status: string;
  reason: string;
}

export interface StackUpdateProgress {
  status: string;
  inProgress: boolean;
  failed: boolean;
  events: StackEventLine[];
}

/** A refusal that carries its own remedy, rather than a bare error string. */
export class StackUpdateError extends Error {
  constructor(
    message: string,
    readonly verdict?: string,
    readonly remedy?: string,
    /**
     * A pre-filled AWS console link that grants the missing permission, when the refusal is
     * one the customer can fix in a click — an account connected before onboarding carried
     * the stack updater. Absent for every other refusal.
     */
    readonly grantUrl?: string,
    /** The role the grant attaches to, shown so the reader can check it before approving. */
    readonly grantRoleName?: string,
  ) {
    super(message);
    this.name = 'StackUpdateError';
  }
}

const parse = async (res: Response): Promise<never> => {
  const body = await res.json().catch(() => ({}));
  // Prefer `error`, but append `detail` when it carries the part AWS actually said —
  // a headline with no detail is what made "Could not reach CloudFormation" useless.
  const headline = body.error ?? `HTTP ${res.status}`;
  const message = body.detail && body.detail !== headline
    ? `${headline}: ${body.detail}`
    : headline;
  throw new StackUpdateError(message, body.verdict, body.remedy, body.grantUrl, body.grantRoleName);
};

/** Builds a change set and describes it. Changes nothing. */
export const previewStackUpdate = async (accountId: string): Promise<StackUpdatePreview> => {
  const res = await apiFetch(
    `/api/xamops/account-manager/stack-update/${encodeURIComponent(accountId)}/preview`,
    { method: 'POST' },
  );
  if (!res.ok) return parse(res);
  return (await res.json()) as StackUpdatePreview;
};

/**
 * Runs a previously previewed change set.
 *
 * Takes the id from the preview rather than recomputing, so what runs is exactly what was
 * shown on screen.
 */
export const executeStackUpdate = async (
  accountId: string,
  changeSetId: string,
): Promise<void> => {
  const res = await apiFetch(
    `/api/xamops/account-manager/stack-update/${encodeURIComponent(accountId)}/execute`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ changeSetId }),
    },
  );
  if (!res.ok) await parse(res);
};

/** Stack status and recent events, for following an update as it runs. */
export const fetchStackProgress = async (accountId: string): Promise<StackUpdateProgress> => {
  const res = await apiFetch(
    `/api/xamops/account-manager/stack-update/${encodeURIComponent(accountId)}/progress`,
  );
  if (!res.ok) return parse(res);
  return (await res.json()) as StackUpdateProgress;
};
