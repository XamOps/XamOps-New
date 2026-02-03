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
import type {
  CreditRequest,
  NewCreditRequest,
  CreditFilter,
  UseCreditsReturn,
} from '../types/credits';

const POLL_INTERVAL = 5000; // 5 seconds (same as original)

export const useCredits = (): UseCreditsReturn => {
  const [creditRequests, setCreditRequests] = useState<CreditRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedFilter, setSelectedFilter] = useState<CreditFilter>('all');
  const [showForm, setShowForm] = useState(false);
  const [newRequestData, setNewRequestData] = useState<NewCreditRequest>({
    awsAccountId: '',
    expectedCredits: null,
    services: '',
    useCase: '',
    priority: 'medium',
  });

  const pollingIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);

  // TODO: Get actual userId from auth context
  const userId = 1;

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

    try {
      const payload = { ...newRequestData, userId };
      const response = await apiFetch('/api/billops/credits', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

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
    });
  }, []);

  /**
   * Manual refresh function
   */
  const refreshData = useCallback(() => {
    fetchRequests();
  }, [fetchRequests]);

  /**
   * Filtered requests based on selected filter
   * Mimics exact behavior from original filteredRequests getter
   */
  const filteredRequests = useMemo(() => {
    if (selectedFilter === 'all') {
      return creditRequests;
    }
    return creditRequests.filter((req) => req.status === selectedFilter);
  }, [creditRequests, selectedFilter]);

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
   * Initialize: Fetch data and start polling
   * Mimics exact behavior from original init() and startPolling() functions
   */
  useEffect(() => {
    isMountedRef.current = true;

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
  }, [fetchRequests]);

  return {
    creditRequests,
    filteredRequests,
    loading,
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
