/**
 * Grafana Dashboard Page
 *
 * Converted from: grafana-dashboard.html
 *
 * Infrastructure Monitoring dashboard that embeds Grafana dashboards
 * based on the selected account configuration.
 *
 * Features:
 * - Fetches Grafana configuration from account settings
 * - Embeds Grafana dashboard in iframe
 * - Help modal with Terraform setup guide
 * - View in Grafana external link
 */

import React, { useEffect, useState, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import GrafanaHeader from '../components/Grafana/GrafanaHeader';
import GrafanaHelpModal from '../components/Grafana/GrafanaHelpModal';
import apiClient from '../utils/apiClient';
import { Account } from '../types/account';

interface GrafanaConfig {
  iframeSrc: string;
  externalUrl: string;
  accountName: string;
}

const GrafanaDashboardPage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();

  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);
  const [grafanaConfig, setGrafanaConfig] = useState<GrafanaConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  /**
   * Load dashboard configuration for the selected account
   */
  const loadDashboardConfig = useCallback(async (account: Account) => {
    const { grafanaIp, grafanaDashboardUid, grafanaDashboardSlug, name } = account;

    if (!grafanaIp) {
      setError(`Grafana IP not configured for "${name}".`);
      setGrafanaConfig(null);
      return;
    }

    if (!grafanaDashboardUid) {
      setError(`Grafana Dashboard UID not configured for "${name}".`);
      setGrafanaConfig(null);
      return;
    }

    if (!grafanaDashboardSlug) {
      setError(`Grafana Dashboard Slug not configured for "${name}".`);
      setGrafanaConfig(null);
      return;
    }

    // Determine protocol based on IP format
    const protocol = grafanaIp.includes(':') ? 'http' : 'https';
    const baseUrl = `${protocol}://${grafanaIp}`;

    // Build iframe URL (kiosk mode)
    const iframeUrl = new URL(`${baseUrl}/d/${grafanaDashboardUid}/${grafanaDashboardSlug}`);
    iframeUrl.searchParams.append('orgId', '1');
    iframeUrl.searchParams.append('kiosk', 'true');

    // Build external URL (with refresh)
    const externalUrl = new URL(`${baseUrl}/d/${grafanaDashboardUid}/${grafanaDashboardSlug}`);
    externalUrl.searchParams.append('orgId', '1');
    externalUrl.searchParams.append('refresh', '10s');

    setGrafanaConfig({
      iframeSrc: iframeUrl.toString(),
      externalUrl: externalUrl.toString(),
      accountName: name,
    });
    setError(null);
  }, []);

  /**
   * Initialize dashboard on mount and when account changes
   */
  useEffect(() => {
    const initDashboard = async () => {
      setIsLoading(true);

      if (selectedAccountIds.length === 0) {
        setError('No account selected. Please select an account from the sidebar.');
        setGrafanaConfig(null);
        setIsLoading(false);
        return;
      }

      try {
        // If accounts are already loaded, use them
        if (accounts.length > 0) {
          const selectedAccount = accounts.find(acc => acc.id === selectedAccountIds[0]);
          if (selectedAccount) {
            loadDashboardConfig(selectedAccount);
          } else {
            setError('Selected account not found.');
            setGrafanaConfig(null);
          }
        } else {
          // Fetch accounts if not loaded
          const fetchedAccounts = await apiClient.get<Account[]>('/api/xamops/account-manager/accounts');
          const selectedAccount = fetchedAccounts.find(acc => acc.id === selectedAccountIds[0]);

          if (selectedAccount) {
            loadDashboardConfig(selectedAccount);
          } else {
            setError('Selected account not found.');
            setGrafanaConfig(null);
          }
        }
      } catch (err) {
        console.error('Failed to load account details:', err);
        setError('Could not load account details.');
        setGrafanaConfig(null);
      } finally {
        setIsLoading(false);
      }
    };

    initDashboard();
  }, [selectedAccountIds, accounts, loadDashboardConfig]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = () => {
      // Re-initialize when account changes via sidebar
      if (accounts.length > 0 && selectedAccountIds.length > 0) {
        const selectedAccount = accounts.find(acc => acc.id === selectedAccountIds[0]);
        if (selectedAccount) {
          loadDashboardConfig(selectedAccount);
        }
      }
    };

    window.addEventListener('account-changed', handleAccountChanged);
    return () => window.removeEventListener('account-changed', handleAccountChanged);
  }, [accounts, selectedAccountIds, loadDashboardConfig]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header */}
      <GrafanaHeader
        accountName={grafanaConfig?.accountName}
        externalUrl={grafanaConfig?.externalUrl}
        error={error}
      />

      {/* Main Content */}
      <div className="flex-1 p-3 bg-gray-200">
        {isLoading ? (
          <div className="w-full h-[calc(100vh-6rem)] bg-white rounded-xl shadow-sm flex items-center justify-center">
            <div className="flex flex-col items-center space-y-4">
              <div className="relative">
                <div className="w-16 h-16 border-4 border-orange-200 rounded-full animate-spin border-t-orange-500"></div>
                <i className="fas fa-chart-line absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 text-orange-500 text-xl"></i>
              </div>
              <p className="text-gray-600 font-medium">Loading Grafana Dashboard...</p>
            </div>
          </div>
        ) : error ? (
          <div className="w-full h-[calc(100vh-6rem)] bg-white rounded-xl shadow-sm flex items-center justify-center">
            <div className="flex flex-col items-center space-y-4 max-w-md text-center px-6">
              <div className="w-20 h-20 bg-gradient-to-br from-red-100 to-orange-100 rounded-2xl flex items-center justify-center">
                <i className="fas fa-exclamation-triangle text-red-500 text-3xl"></i>
              </div>
              <h3 className="text-xl font-bold text-gray-800">Configuration Required</h3>
              <p className="text-gray-600">{error}</p>
              <button
                onClick={() => setIsHelpModalOpen(true)}
                className="mt-4 px-6 py-3 bg-gradient-to-r from-blue-500 to-indigo-600 text-white rounded-xl font-medium hover:from-blue-600 hover:to-indigo-700 transition-all shadow-lg hover:shadow-xl transform hover:-translate-y-0.5"
              >
                <i className="fas fa-book-open mr-2"></i>
                View Setup Guide
              </button>
            </div>
          </div>
        ) : grafanaConfig ? (
          <iframe
            src={grafanaConfig.iframeSrc}
            title="Grafana Dashboard"
            className="w-full h-[calc(100vh-6rem)] border-0 rounded-xl shadow-sm grafana-iframe"
          />
        ) : null}
      </div>

      {/* Floating Help Button */}
      <button
        onClick={() => setIsHelpModalOpen(true)}
        className="fixed bottom-8 right-8 w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-2xl shadow-lg cursor-pointer hover:scale-110 hover:shadow-xl transition-all z-30 group"
        aria-label="Open setup guide"
      >
        <i className="fas fa-question group-hover:animate-pulse"></i>
      </button>

      {/* Help Modal */}
      <GrafanaHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </main>
  );
};

export default GrafanaDashboardPage;
