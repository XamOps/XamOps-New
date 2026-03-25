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
import type {
  Provider,
  AccessType,
  VerificationDetails,
  AzureDetails,
  JenkinsDetails,
  StackUrlResponse,
  AzureCredentials,
} from '../types/addAccount';

interface UseAddAccountReturn {
  // State
  provider: Provider;
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  isAutoConnecting: boolean;
  autoConnected: boolean;
  isPolicyModalOpen: boolean;
  verificationError: string;
  verificationDetails: VerificationDetails;
  azureDetails: AzureDetails;
  jenkinsDetails: JenkinsDetails;

  // Setters
  setProvider: (provider: Provider) => void;
  setAccountName: (name: string) => void;
  setAccessType: (type: AccessType) => void;
  setIsPolicyModalOpen: (open: boolean) => void;
  setVerificationDetails: (details: Partial<VerificationDetails>) => void;
  setAzureDetails: (details: Partial<AzureDetails>) => void;
  setJenkinsDetails: (details: Partial<JenkinsDetails>) => void;

  // Actions
  launchStack: () => Promise<void>;
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
  const [isLoading, setIsLoading] = useState(false);
  const [isVerifying, setIsVerifying] = useState(false);
  const [isAutoConnecting, setIsAutoConnecting] = useState(false);
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
  const pendingExternalIdRef = useRef<string>('');

  // Clean up polling interval on unmount
  useEffect(() => {
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, []);

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

    setIsLoading(true);
    try {
      const res = await apiFetch('/api/xamops/account-manager/generate-stack-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountName: accountName.trim(),
          awsAccountId: '',
          accessType,
        }),
      });

      if (!res.ok) {
        const errorText = await res.text();
        throw new Error(errorText || 'Failed to generate stack URL.');
      }

      const data: StackUrlResponse = await res.json();

      // Open AWS CloudFormation in a new tab
      window.open(data.url, '_blank');

      // Store externalId and switch to verification mode
      setVerificationDetails({ externalId: data.externalId });
      setIsVerifying(true);

      // Only poll for auto-connect if EventBridge is configured on this environment
      if (data.eventBridgeEnabled !== 'true') return;

      pendingExternalIdRef.current = data.externalId;
      setIsAutoConnecting(true);
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
              clearInterval(pollingRef.current!);
              pollingRef.current = null;
              setIsAutoConnecting(false);
              setAutoConnected(true);
            }
          }
        } catch {
          // network blip — will retry next tick
        }
      }, 5000);
    } catch (err) {
      alert(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [accountName, accessType, setVerificationDetails]);

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
    setIsAutoConnecting(false);
    setAutoConnected(false);
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
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
  }, []);

  return {
    // State
    provider,
    accountName,
    accessType,
    isLoading,
    isVerifying,
    isAutoConnecting,
    autoConnected,
    isPolicyModalOpen,
    verificationError,
    verificationDetails,
    azureDetails,
    jenkinsDetails,

    // Setters
    setProvider,
    setAccountName,
    setAccessType,
    setIsPolicyModalOpen,
    setVerificationDetails,
    setAzureDetails,
    setJenkinsDetails,

    // Actions
    launchStack,
    verifyAccount,
    addAzureAccount,
    addJenkinsAccount,
    copyToClipboard,

    // Helpers
    getHeaderText,
    resetForm,
  };
}
