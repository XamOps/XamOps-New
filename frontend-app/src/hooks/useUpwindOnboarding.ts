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
