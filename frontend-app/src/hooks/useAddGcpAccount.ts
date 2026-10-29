/**
 * useAddGcpAccount Hook
 *
 * Custom hook for managing Add GCP Account page state and API calls
 * Converted from: add-gcp-account.html (Alpine.js addGcpAccount() function)
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

import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../utils/apiClient';

interface UseAddGcpAccountReturn {
  // State
  accountName: string;
  projectId: string;
  serviceAccountKey: string;
  billingTable: string;
  isLoading: boolean;
  copyButtonText: string;
  showScript: boolean;
  showGuide: boolean;
  commandText: string;
  fullScript: string;

  // Setters
  setAccountName: (name: string) => void;
  setProjectId: (id: string) => void;
  setServiceAccountKey: (key: string) => void;
  setBillingTable: (table: string) => void;
  setShowScript: (show: boolean) => void;
  setShowGuide: (show: boolean) => void;

  // Actions
  updateCommand: (projectId: string) => void;
  copyCommand: () => Promise<void>;
  addAccount: () => Promise<void>;
  goBack: () => void;
}

const SCRIPT_URL = 'https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh';

export function useAddGcpAccount(): UseAddGcpAccountReturn {
  const navigate = useNavigate();

  // Form State
  const [accountName, setAccountName] = useState('');
  const [projectId, setProjectId] = useState('');
  const [serviceAccountKey, setServiceAccountKey] = useState('');
  const [billingTable, setBillingTable] = useState('');

  // UI State
  const [isLoading, setIsLoading] = useState(false);
  const [copyButtonText, setCopyButtonText] = useState('Copy');
  const [showScript, setShowScript] = useState(false);
  const [showGuide, setShowGuide] = useState(false);
  const [commandText, setCommandText] = useState(
    `bash <(curl -s ${SCRIPT_URL}) YOUR_PROJECT_ID`
  );
  const [fullScript, setFullScript] = useState('# Loading script...');

  /**
   * Load the service account creation script on mount
   */
  useEffect(() => {
    const loadScript = async () => {
      try {
        const response = await fetch(SCRIPT_URL);
        if (response.ok) {
          const text = await response.text();
          setFullScript(text);
        } else {
          setFullScript('# Error: Failed to load script from S3\n# Please check the URL or contact support.');
        }
      } catch (error) {
        console.error('Failed to load script:', error);
        setFullScript('# Error: Network error while loading script\n# Please check your internet connection.');
      }
    };

    loadScript();
  }, []);

  /**
   * Update the command text when project ID changes
   */
  const updateCommand = useCallback((newProjectId: string) => {
    const pid = newProjectId.trim() || 'YOUR_PROJECT_ID';
    setCommandText(`bash <(curl -s ${SCRIPT_URL}) ${pid}`);
  }, []);

  /**
   * Handle project ID change and update command
   */
  const handleSetProjectId = useCallback((id: string) => {
    setProjectId(id);
    updateCommand(id);
  }, [updateCommand]);

  /**
   * Copy command to clipboard
   */
  const copyCommand = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(commandText);
      setCopyButtonText('Copied!');
      setTimeout(() => setCopyButtonText('Copy'), 2000);
    } catch (error) {
      console.error('Failed to copy command:', error);
    }
  }, [commandText]);

  /**
   * Validate and add GCP account
   */
  const addAccount = useCallback(async () => {
    // Validate all required fields
    if (!accountName.trim() || !projectId.trim() || !serviceAccountKey.trim()) {
      alert('Please fill in all required fields:\n\n• Account Name\n• GCP Project ID\n• Service Account Key (JSON)');
      return;
    }

    // Validate JSON format
    try {
      const parsedJson = JSON.parse(serviceAccountKey);
      if (!parsedJson.type || !parsedJson.project_id || !parsedJson.private_key || !parsedJson.client_email) {
        throw new Error('Missing required fields in JSON');
      }
    } catch (e) {
      const errorMessage = e instanceof Error ? e.message : 'Unknown error';
      alert(`Invalid JSON format.\n\nPlease paste the full contents of the key file the script saved (xamops-${projectId.trim()}-key.json).\n\nError: ${errorMessage}`);
      return;
    }

    // Validate billing table format (project.dataset.table) only if provided
    if (billingTable.trim()) {
      const tableParts = billingTable.trim().split('.');
      if (tableParts.length !== 3) {
        alert('Invalid BigQuery table format.\n\nExpected format: project.dataset.table\n\nExample:\nmy-project.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX');
        return;
      }

      // Check if any part is empty
      if (tableParts.some(part => !part.trim())) {
        alert('Invalid BigQuery table format.\n\nEach part (project, dataset, table) must not be empty.');
        return;
      }
    }

    setIsLoading(true);

    try {
      const response = await apiFetch('/api/xamops/account-manager/add-gcp-account', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountName: accountName.trim(),
          projectId: projectId.trim(),
          serviceAccountKey: serviceAccountKey.trim(),
          ...(billingTable.trim() && { billingExportTable: billingTable.trim() }),
        }),
      });

      if (response.ok) {
        const data = await response.json();
        alert(data.message || 'GCP account added successfully!');
        navigate('/account-manager');
      } else {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || errorData.message || 'Failed to add account');
      }
    } catch (error) {
      console.error('Error adding GCP account:', error);
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      alert(`Failed to add GCP account:\n\n${errorMessage}`);
    } finally {
      setIsLoading(false);
    }
  }, [accountName, projectId, serviceAccountKey, billingTable, navigate]);

  /**
   * Navigate back to add account page
   */
  const goBack = useCallback(() => {
    navigate('/add-account');
  }, [navigate]);

  return {
    // State
    accountName,
    projectId,
    serviceAccountKey,
    billingTable,
    isLoading,
    copyButtonText,
    showScript,
    showGuide,
    commandText,
    fullScript,

    // Setters
    setAccountName,
    setProjectId: handleSetProjectId,
    setServiceAccountKey,
    setBillingTable,
    setShowScript,
    setShowGuide,

    // Actions
    updateCommand,
    copyCommand,
    addAccount,
    goBack,
  };
}
