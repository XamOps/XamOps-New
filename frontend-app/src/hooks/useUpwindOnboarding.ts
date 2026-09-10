import { useCallback, useState } from 'react';
import apiClient from '../utils/apiClient';
import type {
  OnboardingAccount, UpwindConnectionCheck, UpwindDisconnectResult, UpwindLaunchUrl,
} from '../types/xamsecure';

/**
 * Connect-and-verify for XamSecure cloud onboarding, shared by both entry points
 * (Add Account → Security Monitoring, and XamSecure → Onboarding).
 *
 * Extracted rather than duplicated specifically because of how the launch URL has to be
 * handled: it carries Xammer's shared Upwind client secret as a stack parameter, so it must be
 * fetched per click, opened immediately, and never put in state, in an href, or anywhere the DOM
 * or an error reporter could serialise it. Two copies of that rule would eventually disagree.
 *
 * @param onChanged called after a status transition, so the caller can re-read its account list
 */
export function useUpwindOnboarding(onChanged?: () => void) {
  const [busyId, setBusyId] = useState<string | null>(null);
  const [checks, setChecks] = useState<Record<string, UpwindConnectionCheck>>({});
  const [disconnects, setDisconnects] = useState<Record<string, UpwindDisconnectResult>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});

  const fail = (id: string, err: unknown, fallback: string) =>
    setErrors((e) => ({ ...e, [id]: err instanceof Error ? err.message : fallback }));

  /** Opens the pre-filled CloudFormation stack in a new tab. */
  const connect = useCallback(async (acct: OnboardingAccount) => {
    const id = acct.cloudAccountId;
    setBusyId(id);
    setErrors((e) => ({ ...e, [id]: '' }));
    try {
      const res = await apiClient.post<UpwindLaunchUrl>(
        '/api/xamsecure/onboarding/aws/launch-url', { cloudAccountId: id });
      window.open(res.launchUrl, '_blank', 'noopener,noreferrer');
      onChanged?.();
    } catch (err) {
      fail(id, err, 'Could not start onboarding. Try again or contact support.');
    } finally {
      setBusyId(null);
    }
  }, [onChanged]);

  /**
   * Asks whether Upwind is reporting assets for the account yet.
   *
   * This is the actual confirmation, not a proxy for one: Upwind's stack registers the account
   * itself, and there is no cloud-account status API to poll instead.
   */
  const check = useCallback(async (acct: OnboardingAccount) => {
    const id = acct.cloudAccountId;
    setBusyId(id);
    setErrors((e) => ({ ...e, [id]: '' }));
    try {
      const res = await apiClient.post<UpwindConnectionCheck>(
        '/api/xamsecure/onboarding/aws/check', { cloudAccountId: id });
      setChecks((c) => ({ ...c, [id]: res }));
      if (res.status === 'CONNECTED') onChanged?.();
    } catch (err) {
      fail(id, err, 'Could not check the connection.');
    } finally {
      setBusyId(null);
    }
  }, [onChanged]);

  /**
   * Disconnects the account from XamSecure.
   *
   * Confirmed by the caller, not here — the customer also has to delete their CloudFormation
   * stack afterwards, and that second step has to be spelled out before they commit, because
   * deleting the stack alone would silently leave the connection registered upstream.
   */
  const disconnect = useCallback(async (acct: OnboardingAccount) => {
    const id = acct.cloudAccountId;
    setBusyId(id);
    setErrors((e) => ({ ...e, [id]: '' }));
    try {
      const res = await apiClient.post<UpwindDisconnectResult>(
        '/api/xamsecure/onboarding/aws/disconnect', { cloudAccountId: id });
      setDisconnects((d) => ({ ...d, [id]: res }));
      onChanged?.();
      return res;
    } catch (err) {
      fail(id, err, 'Could not disconnect the account.');
      return null;
    } finally {
      setBusyId(null);
    }
  }, [onChanged]);

  return { busyId, checks, errors, disconnects, connect, check, disconnect };
}

/** Loads the caller's accounts with their XamSecure connection status. */
export async function fetchOnboardingAccounts(): Promise<OnboardingAccount[]> {
  return apiClient.get<OnboardingAccount[]>('/api/xamsecure/onboarding/status');
}

/**
 * Fetches the host sensor install command.
 *
 * On demand, and POST, because the script embeds a shared credential — same handling as the
 * CloudFormation launch URL. Not held in module state for the same reason.
 */
export async function fetchSensorCommand(): Promise<{ command: string; target: string }> {
  return apiClient.post<{ command: string; target: string }>(
    '/api/xamsecure/onboarding/sensor-command');
}

export interface KubernetesSurveyCommand {
  command: string;
  target: 'kubernetes';
  /** Always 'survey': the survey emits the install command, tailored to the cluster it inspects. */
  phase: string;
  cloudAccountId: string;
  clusterName: string;
  cloudProvider: string;
  requiresKubernetesJobs: boolean;
}

export interface EcsVpcCandidate {
  vpcId: string;
  name: string | null;
  cidrBlock: string | null;
  region: string | null;
  isDefault: boolean;
  subnets: string[];
  /** Account-level, not per VPC — NAT gateways carry no VPC reference upstream. */
  accountHasNatGateway: boolean;
}

export type EcsLaunchType = 'EC2' | 'FARGATE';

export interface EcsDeployment {
  cloudAccountId: string;
  launchType: EcsLaunchType;
  clusters: string[];
  vpcs: EcsVpcCandidate[];
  /** Pre-resolved template parameters, including the sensor credential. */
  parameters: Record<string, string>;
  /** Empty until Upwind gives us the published ECS template URL for this launch type. */
  templateUrl: string;
  /** What the stack deploys. EC2 and Fargate are different stacks, not one stack with a flag. */
  components: string[];
  /** Cluster-manager ingress ports needed from the VPC CIDR. */
  securityGroupPorts: number[];
  notes: string[];
}

/**
 * Resolved CloudFormation parameters for the ECS sensor stack on one account.
 *
 * POST, and the response embeds a shared credential — do not cache it.
 */
export async function fetchEcsDeployment(
  cloudAccountId: string, launchType: EcsLaunchType,
): Promise<EcsDeployment> {
  return apiClient.post<EcsDeployment>(
    '/api/xamsecure/onboarding/ecs-deployment', { cloudAccountId, launchType });
}

export interface EcsLaunch {
  launchUrl: string;
  stackName: string;
  launchType: EcsLaunchType;
  region: string;
  clusterName: string;
}

/**
 * Pre-filled CloudFormation quick-create URL for the ECS sensor stack.
 *
 * The URL carries the shared Sensor credential in its query string, so it is a credential: never
 * hold it in state longer than the click needs, and never put it in an href that survives a render.
 */
export async function fetchEcsLaunchUrl(args: {
  cloudAccountId: string;
  launchType: EcsLaunchType;
  clusterName: string;
  vpcId: string;
  vpcCidr: string;
  subnets: string[];
  region: string;
}): Promise<EcsLaunch> {
  return apiClient.post<EcsLaunch>('/api/xamsecure/onboarding/ecs-launch-url', args);
}

/**
 * Kubernetes sensor survey command for one account and cluster.
 *
 * Parameterized, unlike the host script — the cluster name and provider go into the command — so
 * this takes arguments and is a POST. The response embeds a shared credential, so callers must not
 * cache it or hold it longer than the copy action needs.
 */
export async function fetchKubernetesCommand(
  cloudAccountId: string, clusterName: string,
): Promise<KubernetesSurveyCommand> {
  return apiClient.post<KubernetesSurveyCommand>(
    '/api/xamsecure/onboarding/kubernetes-command', { cloudAccountId, clusterName });
}
