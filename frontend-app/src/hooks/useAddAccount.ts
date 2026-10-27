/**
 * useAddAccount Hook
 *
 * Custom hook for managing Add Account page state and API calls
 * Converted from: add-account.html (Alpine.js addAccount() function)
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Added proper type safety
 * - Used React hooks for state management
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same business logic preserved exactly
 * - Same validation and error handling
 */

import { useState, useCallback, useRef, useEffect } from 'react';
import { apiFetch } from '../utils/apiClient';
import type { CurSourceSelection } from '../components/AddAccount/CurSourceSelector';
import type {
  Provider,
  AccessType,
  AutoConnectStatus,
  VerificationDetails,
  AzureDetails,
  JenkinsDetails,
  StackUrlResponse,
  AzureCredentials,
} from '../types/addAccount';

/**
 * Auto-connect never blocks the customer: the manual form is on screen the whole time, and
 * whichever route finishes first wins. These timings only decide how eagerly we listen and
 * what the banner above the form says.
 */

/** Cadence while the stack is within its expected build time (~1-3 min in practice). */
const POLL_INTERVAL_MS = 5_000;

/** Slower cadence once it's running long — still listening, just less eagerly. */
const SLOW_POLL_INTERVAL_MS = 15_000;

/**
 * When to stop claiming this is nearly done and admit it's running long.
 *
 * A stack can legitimately pass this (many EKS clusters, or the aws-auth Lambda burning its
 * own 300s timeout against an unreachable endpoint), and it can also mean the stack rolled
 * back and nothing is ever coming. We can't tell the two apart from here, so we keep
 * listening and say so.
 */
const SLOW_AFTER_MS = 5 * 60 * 1000;

/** Hard stop, so a tab left open overnight isn't polling the accounts endpoint forever. */
const POLL_GIVE_UP_MS = 30 * 60 * 1000;

/**
 * Fallback name for the role the CloudFormation stack creates.
 *
 * The templates hardcode `RoleName`, so this is knowable without asking the customer — we
 * prefill it and they only have to supply the account ID. But the name is per-environment
 * (local and UAT suffix it with -local / -uat so a test connection can coexist with a real
 * one in the same AWS account), and only the backend knows which template it handed out.
 * So the authoritative value is `roleName` on the generate-stack-url response; these
 * production names are the fallback for a backend that doesn't send it.
 */
export const roleNameFor = (accessType: AccessType): string =>
  accessType === 'read-write' ? 'XamOpsReadWriteRole' : 'XamOpsReadOnlyRole';

interface UseAddAccountReturn {
  // State
  provider: Provider;
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  autoConnectStatus: AutoConnectStatus;
  autoConnected: boolean;
  isPolicyModalOpen: boolean;
  verificationError: string;
  verificationDetails: VerificationDetails;
  /** Where this account's cost data will be read from. */
  curSource: CurSourceSelection;
  /**
   * Quick-create URL for the CUR stack (us-east-1), or null when there is none to launch.
   * Set from the generate-stack-url response and only populated for OWN_CUR accounts.
   */
  curStackUrl: string | null;
  azureDetails: AzureDetails;
  jenkinsDetails: JenkinsDetails;

  // Setters
  setProvider: (provider: Provider) => void;
  setAccountName: (name: string) => void;
  setAccessType: (type: AccessType) => void;
  setIsPolicyModalOpen: (open: boolean) => void;
  setVerificationDetails: (details: Partial<VerificationDetails>) => void;
  setCurSource: (selection: CurSourceSelection) => void;
  setAzureDetails: (details: Partial<AzureDetails>) => void;
  setJenkinsDetails: (details: Partial<JenkinsDetails>) => void;

  // Actions
  launchStack: () => Promise<void>;
  /** Opens the CUR stack quick-create link in a new tab, when one is available. */
  launchCurStack: () => void;
  verifyAccount: () => Promise<void>;
  addAzureAccount: () => Promise<void>;
  addJenkinsAccount: () => Promise<void>;
  copyToClipboard: (text: string) => Promise<boolean>;

  // Helpers
  getHeaderText: () => string;
  resetForm: () => void;
}

export function useAddAccount(): UseAddAccountReturn {
  // State
  const [provider, setProvider] = useState<Provider>('aws');
  const [accountName, setAccountName] = useState('');
  const [accessType, setAccessType] = useState<AccessType>('read-only');

  /**
   * Where this account's cost data will come from.
   *
   * Only the OWN_CUR option ("Create a report in this account") is currently offered in
   * the UI — see CurSourceSelector's OPTIONS list — so this defaults to it to keep the
   * single visible choice pre-selected. When the other options are re-enabled there,
   * revert this default to COST_EXPLORER_ONLY (the pre-CUR behaviour).
   */
  const [curSource, setCurSource] = useState<CurSourceSelection>({
    billingSource: 'OWN_CUR',
  });
  const [curStackUrl, setCurStackUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isVerifying, setIsVerifying] = useState(false);
  const [autoConnectStatus, setAutoConnectStatus] = useState<AutoConnectStatus>('idle');
  const [autoConnected, setAutoConnected] = useState(false);
  const [isPolicyModalOpen, setIsPolicyModalOpen] = useState(false);
  const [verificationError, setVerificationError] = useState('');
  const [verificationDetails, setVerificationDetailsState] = useState<VerificationDetails>({
    awsAccountId: '',
    roleName: '',
    externalId: '',
  });
  const [azureDetails, setAzureDetailsState] = useState<AzureDetails>({
    accountName: '',
    azureCredentialsJson: '',
  });
  const [jenkinsDetails, setJenkinsDetailsState] = useState<JenkinsDetails>({
    url: '',
    username: '',
    token: '',
  });

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const fallbackTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const giveUpTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingExternalIdRef = useRef<string>('');

  /** Tears down every timer. Safe to call when none are running. */
  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    if (fallbackTimerRef.current) {
      clearTimeout(fallbackTimerRef.current);
      fallbackTimerRef.current = null;
    }
    if (giveUpTimerRef.current) {
      clearTimeout(giveUpTimerRef.current);
      giveUpTimerRef.current = null;
    }
  }, []);

  /**
   * (Re)starts the connection poll at the given cadence, replacing any interval already
   * running. Leaves the fallback/give-up timers alone — only the interval is swapped.
   */
  const startPolling = useCallback((intervalMs: number) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      try {
        const accountsRes = await apiFetch('/api/xamops/account-manager/accounts');
        if (accountsRes.ok) {
          const accounts: Array<{ externalId?: string; status: string }> = await accountsRes.json();
          const connected = accounts.find(
            (a) => a.externalId === pendingExternalIdRef.current && a.status === 'CONNECTED'
          );
          if (connected) {
            stopPolling();
            setAutoConnectStatus('idle');
            setAutoConnected(true);
          }
        }
      } catch {
        // network blip — will retry next tick
      }
    }, intervalMs);
  }, [stopPolling]);

  // Clean up both timers on unmount
  useEffect(() => stopPolling, [stopPolling]);

  /**
   * Update verification details partially
   */
  const setVerificationDetails = useCallback((details: Partial<VerificationDetails>) => {
    setVerificationDetailsState(prev => ({ ...prev, ...details }));
  }, []);

  /**
   * Update Azure details partially
   */
  const setAzureDetails = useCallback((details: Partial<AzureDetails>) => {
    setAzureDetailsState(prev => ({ ...prev, ...details }));
  }, []);

  /**
   * Update Jenkins details partially
   */
  const setJenkinsDetails = useCallback((details: Partial<JenkinsDetails>) => {
    setJenkinsDetailsState(prev => ({ ...prev, ...details }));
  }, []);

  /**
   * Get header text based on provider
   */
  const getHeaderText = useCallback((): string => {
    switch (provider) {
      case 'aws':
        return 'Connect your AWS account securely using CloudFormation';
      case 'gcp':
        return 'Connect your Google Cloud project using a Service Account';
      case 'azure':
        return 'Connect your Azure subscription using a Service Principal';
      case 'jenkins':
        return 'Connect your Jenkins Server via API Token';
      default:
        return 'Select a cloud provider to begin';
    }
  }, [provider]);

  /**
   * Launch AWS CloudFormation Stack
   */
  const launchStack = useCallback(async () => {
    if (!accountName.trim()) {
      alert('Please enter an account name.');
      return;
    }

    // What the stack's CurS3Bucket parameter gets. Blank means "attach no CUR
    // permissions", which is right for an account under a XamOps payer account and for
    // one with no report at all.
    //
    // 'AUTO' rather than a computed name for the create-a-report case: the customer's AWS
    // account id is not known until verification, and the template runs in their account
    // so it can derive xamops-cur-<account> itself.
    const curBucketParam =
      curSource.billingSource === 'OWN_CUR'
        ? 'AUTO'
        : curSource.billingSource === 'EXISTING_CUR'
          ? (curSource.bucket ?? '').trim()
          : '';

    if (curSource.billingSource === 'EXISTING_CUR' && !curBucketParam) {
      alert('Enter the S3 bucket holding the existing Cost and Usage Report.');
      return;
    }

    setIsLoading(true);
    try {
      const res = await apiFetch('/api/xamops/account-manager/generate-stack-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountName: accountName.trim(),
          awsAccountId: '',
          accessType,
          curS3Bucket: curBucketParam,
          // Decides whether the connection stack creates the report. Without it the
          // template's default applies and every account gets one — including the ones
          // covered by a payer's report, and the ones whose payer never enabled billing
          // access, where creating one is impossible and the stack would fail.
          awsBillingSource: curSource.billingSource,
        }),
      });

      if (!res.ok) {
        const errorText = await res.text();
        throw new Error(errorText || 'Failed to generate stack URL.');
      }

      const data: StackUrlResponse = await res.json();

      // The CUR stack (us-east-1) is a separate, non-nested stack the customer launches on
      // its own. The backend only sends curUrl for OWN_CUR accounts, so null here means
      // there is no second stack to launch and the button stays hidden.
      setCurStackUrl(data.curUrl ?? null);

      // Open AWS CloudFormation in a new tab
      window.open(data.url, '_blank', 'noopener,noreferrer');

      // Store externalId and switch to verification mode. roleName is prefilled from the
      // template's hardcoded RoleName so manual entry is one field, not two — the customer
      // can still edit it if they deployed a modified template.
      setVerificationDetails({
        externalId: data.externalId,
        roleName: data.roleName || roleNameFor(accessType),
      });
      setIsVerifying(true);

      // Only poll for auto-connect if EventBridge is configured on this environment
      if (data.eventBridgeEnabled !== 'true') return;

      pendingExternalIdRef.current = data.externalId;
      setAutoConnectStatus('listening');
      stopPolling();
      startPolling(POLL_INTERVAL_MS);

      fallbackTimerRef.current = setTimeout(() => {
        setAutoConnectStatus('slow');
        startPolling(SLOW_POLL_INTERVAL_MS);
      }, SLOW_AFTER_MS);

      giveUpTimerRef.current = setTimeout(() => {
        stopPolling();
        setAutoConnectStatus('stopped');
      }, POLL_GIVE_UP_MS);
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [accountName, accessType, curSource, setVerificationDetails, stopPolling, startPolling]);

  /**
   * Launch the CUR stack (us-east-1) in a new tab.
   *
   * Separate from launchStack because it is a second, independent stack: the connection
   * stack opens automatically on generate, while this one is launched on demand from the
   * Verify step and only when the backend provided a curUrl.
   */
  const launchCurStack = useCallback(() => {
    if (curStackUrl) window.open(curStackUrl, '_blank', 'noopener,noreferrer');
  }, [curStackUrl]);

  /**
   * Verify AWS Account connection
   */
  const verifyAccount = useCallback(async () => {
    setIsLoading(true);
    setVerificationError('');

    try {
      const res = await apiFetch('/api/xamops/account-manager/verify-account', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(verificationDetails),
      });

      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        throw new Error(errorData.message || 'Verification failed.');
      }

      // Success - redirect to account manager
      window.location.href = '/account-manager';
    } catch (err) {
      setVerificationError(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [verificationDetails]);

  /**
   * Add Azure Account
   */
  const addAzureAccount = useCallback(async () => {
    setIsLoading(true);

    try {
      // Parse and validate the JSON credentials
      const parsedJson = JSON.parse(azureDetails.azureCredentialsJson);

      // Convert snake_case/kebab-case to camelCase
      const toCamel = (s: string): string =>
        s.replace(/([-_][a-z])/gi, ($1) =>
          $1.toUpperCase().replace('-', '').replace('_', '')
        );

      const credentials: Record<string, string> = Object.keys(parsedJson).reduce(
        (acc, key) => {
          acc[toCamel(key)] = parsedJson[key];
          return acc;
        },
        {} as Record<string, string>
      );

      const payload: AzureCredentials = {
        tenantId: credentials.tenantId,
        subscriptionId: credentials.subscriptionId,
        clientId: credentials.clientId,
        clientSecret: credentials.clientSecret,
        accountName: azureDetails.accountName,
      };

      // Validate required fields
      const requiredFields: (keyof AzureCredentials)[] = [
        'tenantId',
        'subscriptionId',
        'clientId',
        'clientSecret',
      ];
      const missingFields = requiredFields.filter((field) => !payload[field]);

      if (missingFields.length > 0) {
        throw new Error(`Missing fields: ${missingFields.join(', ')}`);
      }

      const res = await apiFetch('/api/xamops/account-manager/azure', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        const errorText = await res.text();
        throw new Error(errorText || 'Failed to add Azure account.');
      }

      // The backend saves the account even when the cost export could not be created, and says so
      // with these headers. Without surfacing them the user is told the connection succeeded and then
      // sees 0.00 for every cost figure, which is indistinguishable from a subscription that spent
      // nothing.
      const warning = res.headers.get('X-XamOps-Warning');
      if (warning) {
        alert(
          res.headers.get('X-XamOps-Warning-Detail') ??
            'The Azure account was connected, but cost data will not be available.'
        );
        window.location.href = '/account-manager';
        return;
      }

      alert('Azure account added successfully!');
      window.location.href = '/account-manager';
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [azureDetails]);

  /**
   * Add Jenkins Account
   */
  const addJenkinsAccount = useCallback(async () => {
    setIsLoading(true);

    try {
      const res = await apiFetch('/api/cicd/config/jenkins', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jenkinsUrl: jenkinsDetails.url,
          username: jenkinsDetails.username,
          apiToken: jenkinsDetails.token,
        }),
      });

      if (!res.ok) {
        throw new Error('Failed to connect to Jenkins.');
      }

      alert('Jenkins server connected successfully!');
      window.location.href = '/account-manager';
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [jenkinsDetails]);

  /**
   * Copy text to clipboard
   */
  const copyToClipboard = useCallback(async (text: string): Promise<boolean> => {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      return false;
    }
  }, []);

  /**
   * Reset form to initial state
   */
  const resetForm = useCallback(() => {
    setAccountName('');
    setAccessType('read-only');
    setIsVerifying(false);
    setAutoConnected(false);
    setAutoConnectStatus('idle');
    setCurStackUrl(null);
    stopPolling();
    setVerificationError('');
    setVerificationDetailsState({
      awsAccountId: '',
      roleName: '',
      externalId: '',
    });
    setAzureDetailsState({
      accountName: '',
      azureCredentialsJson: '',
    });
    setJenkinsDetailsState({
      url: '',
      username: '',
      token: '',
    });
  }, [stopPolling]);

  return {
    // State
    provider,
    accountName,
    accessType,
    isLoading,
    isVerifying,
    autoConnectStatus,
    autoConnected,
    isPolicyModalOpen,
    verificationError,
    verificationDetails,
    azureDetails,
    jenkinsDetails,
    curSource,
    curStackUrl,

    // Setters
    setProvider,
    setAccountName,
    setAccessType,
    setIsPolicyModalOpen,
    setVerificationDetails,
    setAzureDetails,
    setJenkinsDetails,
    setCurSource,

    // Actions
    launchStack,
    launchCurStack,
    verifyAccount,
    addAzureAccount,
    addJenkinsAccount,
    copyToClipboard,

    // Helpers
    getHeaderText,
    resetForm,
  };
}
