/**
 * useCredits Hook
 *
 * Converted from: billops/credits.html (creditManager Alpine.js component)
 *
 * Manages Multicloud Credits Management data fetching with:
 * - Real-time polling every 5 seconds
 * - Form state management
 * - Filter functionality
 * - Toast notifications
 * - Proper cleanup on unmount
 * - Multicloud support (AWS, GCP, Azure)
 *
 * What stayed the same:
 * - Same API endpoints: GET /api/billops/credits/user/:userId and POST /api/billops/credits
 * - Same polling interval (5 seconds)
 * - Same form validation logic
 * - All business logic preserved exactly
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { apiFetch } from '../utils/apiClient';
import { useAccounts } from '../contexts/AccountContext';
import { useAuth } from '../contexts/AuthContext';
import type {
  CreditRequest,
  NewCreditRequest,
  CreditFilter,
  UseCreditsReturn,
} from '../types/credits';

const POLL_INTERVAL = 5000; // 5 seconds (same as original)

export const useCredits = (): UseCreditsReturn => {
  const { selectedAccountIds, accounts, isLoading: accountsLoading } = useAccounts();
  const { userProfile } = useAuth();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [creditRequests, setCreditRequests] = useState<CreditRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedFilter, setSelectedFilter] = useState<CreditFilter>('all');
  const [showForm, setShowForm] = useState(false);
  const [newRequestData, setNewRequestData] = useState<NewCreditRequest>({
    awsAccountId: '',
    expectedCredits: null,
    services: '',
    useCase: '',
    priority: 'medium',
    files: [],
  });

  const pollingIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);

  const userId = userProfile?.id ?? 0;

  /**
   * Fetch credit requests from API
   * Mimics exact behavior from original fetchRequests() function
   */
  const fetchRequests = useCallback(async () => {
    try {
      const response = await apiFetch(`/api/billops/credits/user/${userId}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error('Network response was not ok');
      }

      const data: CreditRequest[] = await response.json();

      if (isMountedRef.current) {
        setCreditRequests(data);
        setError(null);
      }
    } catch (err) {
      console.error('Error fetching credit requests:', err);
      if (isMountedRef.current) {
        setError('Failed to load credit requests. Please try again.');
      }
    } finally {
      if (isMountedRef.current) {
        setLoading(false);
      }
    }
  }, [userId]);

  /**
   * Submit new credit request
   * Mimics exact behavior from original submitRequest() function
   */
  const submitRequest = useCallback(async () => {
    // Validate form
    if (
      !newRequestData.awsAccountId ||
      !newRequestData.expectedCredits ||
      newRequestData.expectedCredits <= 0 ||
      !newRequestData.services ||
      !newRequestData.useCase
    ) {
      showNotification('Please fill all required fields correctly.', 'error');
      return;
    }

    setIsSubmitting(true);
    try {
      const files = newRequestData.files || [];

      // Attachments require a multipart submission; without them keep the original JSON call.
      let response: Response;
      if (files.length > 0) {
        const form = new FormData();
        form.append('awsAccountId', newRequestData.awsAccountId);
        form.append('expectedCredits', String(newRequestData.expectedCredits));
        form.append('services', newRequestData.services);
        form.append('useCase', newRequestData.useCase);
        form.append('userId', String(userId));
        files.forEach((file) => form.append('files', file));

        response = await apiFetch('/api/billops/credits', {
          method: 'POST',
          credentials: 'include',
          body: form,
        });
      } else {
        const { files: _ignored, ...payload } = newRequestData;
        response = await apiFetch('/api/billops/credits', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ...payload, userId }),
        });
      }

      if (!response.ok) {
        throw new Error('Submission failed');
      }

      // Refresh data after successful submission
      await fetchRequests();

      // Close form and reset
      if (isMountedRef.current) {
        setShowForm(false);
        resetForm();
        showNotification('Request submitted successfully!', 'success');
      }
    } catch (err) {
      console.error('Error submitting credit request:', err);
      showNotification('Failed to submit request.', 'error');
    } finally {
      if (isMountedRef.current) {
        setIsSubmitting(false);
      }
    }
  }, [newRequestData, userId, fetchRequests]);

  /**
   * Reset form to initial state
   */
  const resetForm = useCallback(() => {
    setNewRequestData({
      awsAccountId: '',
      expectedCredits: null,
      services: '',
      useCase: '',
      priority: 'medium',
      files: [],
    });
  }, []);

  /**
   * Add supporting documents to the pending request. Any file type and size is accepted;
   * duplicates of an already-attached file are skipped.
   */
  const addFiles = useCallback((incoming: File[]) => {
    if (incoming.length === 0) return;

    setNewRequestData((prev) => {
      const existing = prev.files || [];
      const isNew = (file: File) => !existing.some((f) => f.name === file.name && f.size === file.size);
      return { ...prev, files: [...existing, ...incoming.filter(isNew)] };
    });
  }, []);

  /**
   * Remove a pending supporting document by position
   */
  const removeFile = useCallback((index: number) => {
    setNewRequestData((prev) => ({
      ...prev,
      files: (prev.files || []).filter((_, i) => i !== index),
    }));
  }, []);

  /**
   * Manual refresh function
   */
  const refreshData = useCallback(() => {
    fetchRequests();
  }, [fetchRequests]);

  /**
   * Filtered requests based on selected account + status filter
   */
  const filteredRequests = useMemo(() => {
    let results = selectedAccountId
      ? creditRequests.filter((req) => req.awsAccountId === selectedAccountId)
      : creditRequests;
    if (selectedFilter !== 'all') {
      results = results.filter((req) => req.status === selectedFilter);
    }
    return results;
  }, [creditRequests, selectedFilter, selectedAccountId]);

  /**
   * Calculate total requested amount
   */
  const totalRequested = useMemo(() => {
    return creditRequests.reduce((sum, req) => sum + parseFloat(String(req.expectedCredits || 0)), 0);
  }, [creditRequests]);

  /**
   * Calculate pending count (not approved or rejected)
   */
  const pendingCount = useMemo(() => {
    return creditRequests.filter(
      (req) => req.status !== 'approved' && req.status !== 'rejected'
    ).length;
  }, [creditRequests]);

  /**
   * Auto-populate awsAccountId in form when selected account changes
   */
  useEffect(() => {
    if (selectedAccountId) {
      setNewRequestData((prev) => ({ ...prev, awsAccountId: selectedAccountId }));
    }
  }, [selectedAccountId]);

  /**
   * Initialize: Fetch data and start polling
   * Mimics exact behavior from original init() and startPolling() functions
   */
  useEffect(() => {
    isMountedRef.current = true;

    // Don't fetch if accounts are still loading or user has no accounts
    if (accountsLoading) return;
    if (accounts.length === 0) {
      setLoading(false);
      return () => { isMountedRef.current = false; };
    }

    // Initial fetch
    fetchRequests();

    // Start polling every 5 seconds
    pollingIntervalRef.current = window.setInterval(() => {
      console.log('🔄 Auto-refreshing credits data...');
      fetchRequests();
    }, POLL_INTERVAL);

    // Cleanup on unmount
    return () => {
      isMountedRef.current = false;
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, [fetchRequests, accounts.length, accountsLoading]);

  return {
    creditRequests,
    filteredRequests,
    loading,
    isSubmitting,
    error,
    selectedFilter,
    showForm,
    newRequestData,
    totalRequested,
    pendingCount,
    setShowForm,
    setSelectedFilter,
    setNewRequestData,
    submitRequest,
    resetForm,
    refreshData,
    addFiles,
    removeFile,
  };
};

/**
 * Show toast notification
 * Mimics exact behavior from original showNotification() function
 */
function showNotification(message: string, type: 'success' | 'error') {
  const notification = document.createElement('div');
  notification.className = `fixed top-4 right-4 px-6 py-3 rounded-lg text-white z-50 animate-fade-in ${type === 'success' ? 'bg-green-600' : 'bg-red-600'
    }`;
  notification.textContent = message;
  document.body.appendChild(notification);
  setTimeout(() => notification.remove(), 3000);
}

export default useCredits;
