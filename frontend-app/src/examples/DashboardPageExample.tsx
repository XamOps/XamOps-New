/**
 * Dashboard Page Example
 *
 * This is an example of how to create a dashboard page that:
 * 1. Uses the Sidebar component
 * 2. Accesses account and auth context
 * 3. Listens for account change events
 * 4. Makes API calls using apiClient
 *
 * Copy this pattern when converting other pages to React
 */

import React, { useEffect, useState } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';

interface DashboardData {
  // Define your dashboard data structure here
  totalCost?: number;
  resources?: number;
  // ... other fields
}

const DashboardPageExample: React.FC = () => {
  const { hasAnyRole, userProfile } = useAuth();
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();

  const [dashboardData, setDashboardData] = useState<DashboardData | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Fetch dashboard data from backend
   */
  const fetchDashboardData = async () => {
    if (selectedAccountIds.length === 0) {
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      // Example API call - replace with your actual endpoint
      const data = await apiClient.get<DashboardData>(
        `/api/xamops/dashboard?accountIds=${selectedAccountIds.join(',')}`
      );
      setDashboardData(data);
    } catch (err) {
      console.error('Failed to fetch dashboard data:', err);
      setError('Failed to load dashboard data');
    } finally {
      setIsLoading(false);
    }
  };

  /**
   * Load data on mount and when accounts change
   */
  useEffect(() => {
    fetchDashboardData();
  }, [selectedAccountIds]);

  /**
   * Listen for account change events (for soft navigation)
   */
  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      console.log('Account changed:', event.detail);
      fetchDashboardData();
    };

    window.addEventListener('account-changed', handleAccountChanged as EventListener);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged as EventListener);
    };
  }, []);

  /**
   * Check permissions
   */
  if (!hasAnyRole('ROLE_XAMOPS')) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-red-600">Access Denied</h1>
          <p className="text-gray-600 mt-2">You don't have permission to view this page.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="p-8">
      {/* Header */}
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-600 mt-2">
          Viewing data for: <span className="font-semibold">{selectedAccountDisplay.name}</span>
        </p>
        {userProfile && (
          <p className="text-sm text-gray-500">
            Logged in as: {userProfile.username || userProfile.email}
          </p>
        )}
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <div className="text-center">
            <svg
              className="animate-spin h-12 w-12 text-indigo-600 mx-auto"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
            <p className="mt-4 text-gray-600">Loading dashboard data...</p>
          </div>
        </div>
      )}

      {/* Error State */}
      {error && !isLoading && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <div className="flex">
            <svg
              className="h-5 w-5 text-red-400"
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 20 20"
              fill="currentColor"
            >
              <path
                fillRule="evenodd"
                d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                clipRule="evenodd"
              />
            </svg>
            <div className="ml-3">
              <h3 className="text-sm font-medium text-red-800">Error</h3>
              <p className="text-sm text-red-700 mt-1">{error}</p>
            </div>
          </div>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && !error && selectedAccountIds.length === 0 && (
        <div className="text-center py-12">
          <svg
            className="mx-auto h-12 w-12 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"
            />
          </svg>
          <h3 className="mt-2 text-sm font-medium text-gray-900">No account selected</h3>
          <p className="mt-1 text-sm text-gray-500">
            Please select an account from the sidebar to view dashboard data.
          </p>
        </div>
      )}

      {/* Dashboard Content */}
      {!isLoading && !error && dashboardData && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {/* Example Card 1 */}
          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-gray-600">Total Cost</p>
                <p className="text-2xl font-bold text-gray-900 mt-2">
                  ${dashboardData.totalCost?.toFixed(2) || '0.00'}
                </p>
              </div>
              <div className="bg-green-100 rounded-full p-3">
                <svg
                  className="h-6 w-6 text-green-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
              </div>
            </div>
          </div>

          {/* Example Card 2 */}
          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-gray-600">Resources</p>
                <p className="text-2xl font-bold text-gray-900 mt-2">
                  {dashboardData.resources || 0}
                </p>
              </div>
              <div className="bg-blue-100 rounded-full p-3">
                <svg
                  className="h-6 w-6 text-blue-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"
                  />
                </svg>
              </div>
            </div>
          </div>

          {/* Add more cards as needed */}
        </div>
      )}
    </div>
  );
};

export default DashboardPageExample;
