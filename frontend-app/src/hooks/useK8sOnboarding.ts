/**
 * useK8sOnboarding Hook
 *
 * Thin API-client wrapper over the six two-phase EKS onboarding endpoints
 * (`/api/xamops/k8s/onboarding/*`): status, preflight, permissions, connect-token,
 * revoke-key and disable-automation.
 *
 * Shape choice: the hook returns a set of stable `useCallback` request functions and
 * holds NO loading/error state of its own. Each drawer in the guide owns a distinct
 * slice of the flow (connect vs enable-automation), so each owns its own loading/error
 * state around these calls rather than sharing one hook-level status that would couple
 * the drawers together.
 *
 * `accountId` is the page's `accountIds` URL param (^[0-9]{1,12}$) — the SAME value
 * `/k8s/capabilities` uses. It is NOT the AWS 12-digit account number.
 *
 * Follows the healthy apiClient convention (useEksDashboard.ts, useAgentInstallConfig.ts):
 * calls apiClient.get/post/delete with NO options object so the 45s client-side deadline
 * stays armed. Do NOT pass {signal} — that disables the deadline.
 */

import { useCallback, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import type { OnboardingFeatureWire } from '../components/CloudK8s/onboarding/featureCatalogPresentation';

/** The two onboarding phases: read-only connect, then node automation. */
export type Phase = 'READ_ONLY' | 'AUTOMATION';

/** `GET /clusters/{name}` — whether the cluster is registered and its known region/tokens. */
export interface OnboardingStatus {
  clusterName: string;
  registered: boolean;
  region?: string;
  tokens?: unknown[];
}

/** One preflight check row from `POST /clusters/{name}/preflight`. */
export interface PreflightCheck {
  id: string;
  result: 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED' | 'UNKNOWN';
  blocksPhases: string[];
  title: string;
  detail?: string | null;
  remediation?: { text: string; command?: string | null } | null;
}

/** `POST /clusters/{name}/preflight` — the full preflight result for a phase. */
export interface PreflightResult {
  ranAt: string;
  checks: PreflightCheck[];
  blocking: boolean;
  identityMode: string | null;
  nodeScaler: string | null;
}

/** One AWS role block in the permission manifest. */
export interface PermissionAwsRole {
  role: 'read' | 'write';
  boundTo: string;
  policy: {
    Version: string;
    Statement: Array<{
      Sid: string;
      Effect: string;
      Action: string[];
      Resource: string;
      Condition?: Record<string, unknown>;
    }>;
  } | null;
}

/** `GET /permissions` — the manifest the user reviews before issuing a connect token. */
export interface PermissionManifest {
  version: string;
  phase: string;
  clusterName: string;
  region: string;
  accountId: string;
  identityMode: string | null;
  nodeScaler: string | null;
  kubernetes: unknown[];
  aws: PermissionAwsRole[];
  adminPermissions: string[];
  features: OnboardingFeatureWire[];
  nodeRemoval: boolean;
  notes: string[];
  hash: string;
}

/** `POST /clusters/{name}/connect-token` — the short-lived connect token + verbatim command. */
export interface ConnectTokenResponse {
  token: string;
  tokenPrefix: string;
  expiresAt: string;
  ttlSeconds: number;
  purpose: string;
  command: string;
}

/** Arguments for the `/permissions` manifest request. */
export interface PermissionsArgs {
  clusterName: string;
  region: string;
  phase: Phase;
  createReadRole: boolean;
  features?: string[];
}

/** Arguments for the `/connect-token` request. */
export interface ConnectTokenArgs {
  region: string;
  phase: Phase;
  createReadRole: boolean;
  features?: string[];
}

const BASE = '/api/xamops/k8s/onboarding';

export interface UseK8sOnboardingReturn {
  getStatus: (clusterName: string) => Promise<OnboardingStatus>;
  runPreflight: (clusterName: string, region: string, phase: Phase) => Promise<PreflightResult>;
  getPermissions: (args: PermissionsArgs) => Promise<PermissionManifest>;
  issueConnectToken: (clusterName: string, args: ConnectTokenArgs) => Promise<ConnectTokenResponse>;
  revokeKey: (clusterName: string, agent: 'data' | 'automation') => Promise<void>;
  disableAutomation: (clusterName: string) => Promise<void>;
}

export function useK8sOnboarding({ accountId }: { accountId: string }): UseK8sOnboardingReturn {
  // The accountId query part, encoded once and reused by every request.
  const accountQuery = useMemo(() => `accountId=${encodeURIComponent(accountId)}`, [accountId]);

  const getStatus = useCallback(
    (clusterName: string) =>
      apiClient.get<OnboardingStatus>(
        `${BASE}/clusters/${encodeURIComponent(clusterName)}?${accountQuery}`
      ),
    [accountQuery]
  );

  const runPreflight = useCallback(
    (clusterName: string, region: string, phase: Phase) =>
      apiClient.post<PreflightResult>(
        `${BASE}/clusters/${encodeURIComponent(clusterName)}/preflight?${accountQuery}`,
        { region, phase }
      ),
    [accountQuery]
  );

  const getPermissions = useCallback(
    ({ clusterName, region, phase, createReadRole, features }: PermissionsArgs) => {
      let url =
        `${BASE}/permissions?${accountQuery}` +
        `&clusterName=${encodeURIComponent(clusterName)}` +
        `&region=${encodeURIComponent(region)}` +
        `&phase=${encodeURIComponent(phase)}` +
        `&createReadRole=${createReadRole}`;
      if (features) {
        url += `&features=${encodeURIComponent(features.join(','))}`;
      }
      return apiClient.get<PermissionManifest>(url);
    },
    [accountQuery]
  );

  const issueConnectToken = useCallback(
    (clusterName: string, { region, phase, createReadRole, features }: ConnectTokenArgs) =>
      apiClient.post<ConnectTokenResponse>(
        `${BASE}/clusters/${encodeURIComponent(clusterName)}/connect-token?${accountQuery}`,
        { region, phase, createReadRole, ...(features ? { features } : {}) }
      ),
    [accountQuery]
  );

  const revokeKey = useCallback(
    (clusterName: string, agent: 'data' | 'automation') =>
      apiClient.delete<void>(
        `${BASE}/clusters/${encodeURIComponent(clusterName)}/keys/${encodeURIComponent(agent)}?${accountQuery}`
      ),
    [accountQuery]
  );

  const disableAutomation = useCallback(
    (clusterName: string) =>
      apiClient.post<void>(
        `${BASE}/clusters/${encodeURIComponent(clusterName)}/disable-automation?${accountQuery}`
      ),
    [accountQuery]
  );

  return { getStatus, runPreflight, getPermissions, issueConnectToken, revokeKey, disableAutomation };
}

export default useK8sOnboarding;
