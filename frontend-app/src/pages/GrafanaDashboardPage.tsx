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
import GrafanaHelpModal from '../components/Grafana/GrafanaHelpModal';
import apiClient from '../utils/apiClient';
import { Account } from '../types/account';
import { useTopbar } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
import DemoGrafanaDashboard from '../components/Grafana/DemoGrafanaDashboard';
import { isDemoMode } from '../demo/demoConfig';

interface GrafanaConfig {
  iframeSrc: string;
  externalUrl: string;
  accountName: string;
}

const GrafanaDashboardPage: React.FC = () => {
  const { selectedAccountIds, accounts, fetchAccounts } = useAccounts();

  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);
  const [grafanaConfig, setGrafanaConfig] = useState<GrafanaConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Inline self-serve configuration form state (shown in the "Configuration Required" state)
  const [formIp, setFormIp] = useState<string>('');
  const [formUid, setFormUid] = useState<string>('');
  const [formSlug, setFormSlug] = useState<string>('');
  const [isSaving, setIsSaving] = useState<boolean>(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // The account currently selected in the sidebar (the one being configured)
  const selectedAccount: Account | undefined =
    selectedAccountIds.length > 0
      ? accounts.find(acc => acc.id === selectedAccountIds[0])
      : undefined;

  // Seed the form from the selected account's existing values, re-seeding when
  // the selection changes so switching accounts shows that account's config.
  useEffect(() => {
    setFormIp(selectedAccount?.grafanaIp ?? '');
    setFormUid(selectedAccount?.grafanaDashboardUid ?? '');
    setFormSlug(selectedAccount?.grafanaDashboardSlug ?? '');
    setSaveError(null);
  }, [selectedAccount?.id, selectedAccount?.grafanaIp, selectedAccount?.grafanaDashboardUid, selectedAccount?.grafanaDashboardSlug]);

  /**
   * Extract a user-facing message from an apiClient error. apiFetchJson throws
   * `HTTP <status>: <body>` where the body is the backend JSON `{"message":"..."}`.
   */
  const extractErrorMessage = (err: unknown): string => {
    const raw = err instanceof Error ? err.message : String(err);
    const jsonStart = raw.indexOf('{');
    if (jsonStart !== -1) {
      try {
        const parsed = JSON.parse(raw.slice(jsonStart)) as { message?: string };
        if (parsed && typeof parsed.message === 'string' && parsed.message.trim()) {
          return parsed.message;
        }
      } catch {
        // fall through to raw message
      }
    }
    return raw;
  };

  /**
   * Persist the Grafana config through the existing monitoring endpoint, then
   * refresh account data so the embed renders without a hard page reload.
   */
  const handleConfigSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!selectedAccount) {
      setSaveError('No account selected. Please select an account from the sidebar.');
      return;
    }

    setIsSaving(true);
    setSaveError(null);

    try {
      await apiClient.post(
        `/api/xamops/account-manager/accounts/${selectedAccount.id}/monitoring`,
        {
          grafanaIp: formIp.trim(),
          grafanaDashboardUid: formUid.trim(),
          grafanaDashboardSlug: formSlug.trim(),
        }
      );
      // Re-fetch accounts so the selected account carries the new grafana fields;
      // the init effect (keyed on accounts) re-runs loadDashboardConfig automatically.
      await fetchAccounts();
    } catch (err) {
      setSaveError(extractErrorMessage(err));
    } finally {
      setIsSaving(false);
    }
  };

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

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    if (grafanaConfig?.externalUrl) {
      setActions(
        <a
          href={grafanaConfig.externalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 px-4 py-2 bg-white text-gray-700 text-xs font-bold rounded-xl border border-gray-200 hover:bg-gray-50 hover:border-gray-300 hover:shadow-md transition-all active:scale-95"
        >
          <span>View in Grafana</span>
          <i className="fas fa-external-link-alt text-xs text-orange-500"></i>
        </a>
      );
    } else {
      clearActions(); // Clear if no URL (e.g. error or loading)
    }
    return () => clearActions();
  }, [setActions, clearActions, grafanaConfig?.externalUrl]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header */}
      {/* Main Content */}

      {/* Main Content */}
      <div className="flex-1 p-3 bg-gray-200">
        {isLoading ? (
          <div className="w-full h-[calc(100vh-6rem)] bg-white rounded-xl shadow-sm overflow-hidden flex flex-col">
            <div className="h-16 border-b border-gray-100 flex items-center px-6 justify-between">
              <Skeleton variant="text" width={200} height={24} />
              <Skeleton variant="rounded" width={100} height={36} />
            </div>
            <div className="flex-1 p-6 space-y-6">
              <div className="grid grid-cols-4 gap-6">
                <Skeleton variant="rounded" height={100} width="100%" className="col-span-1" />
                <Skeleton variant="rounded" height={100} width="100%" className="col-span-1" />
                <Skeleton variant="rounded" height={100} width="100%" className="col-span-1" />
                <Skeleton variant="rounded" height={100} width="100%" className="col-span-1" />
              </div>
              <div className="grid grid-cols-2 gap-6 h-96">
                <Skeleton variant="rounded" height="100%" width="100%" />
                <Skeleton variant="rounded" height="100%" width="100%" />
              </div>
            </div>
          </div>
        ) : error ? (
          <div className="w-full h-[calc(100vh-6rem)] bg-white rounded-xl shadow-sm flex items-center justify-center overflow-y-auto">
            <div className="flex flex-col items-center space-y-5 w-full max-w-md text-center px-6 py-10">
              <div className="w-20 h-20 bg-gradient-to-br from-red-100 to-orange-100 rounded-2xl flex items-center justify-center">
                <i className="fas fa-exclamation-triangle text-red-500 text-3xl"></i>
              </div>
              <h3 className="text-xl font-bold text-gray-800">Configuration Required</h3>
              <p className="text-gray-600">{error}</p>

              {/* Inline self-serve configuration form */}
              <form onSubmit={handleConfigSubmit} className="w-full text-left space-y-4 mt-2">
                <div>
                  <label htmlFor="grafana-ip" className="block text-sm font-semibold text-gray-700 mb-1">
                    Grafana IP / Host
                  </label>
                  <input
                    id="grafana-ip"
                    type="text"
                    value={formIp}
                    onChange={(e) => setFormIp(e.target.value)}
                    placeholder="grafana.example.com or 1.2.3.4:3000"
                    disabled={isSaving}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:bg-gray-50 disabled:text-gray-400"
                  />
                  <p className="text-xs text-gray-500 mt-1">
                    Host or IP of your Grafana server. Include a port (e.g. <code>:3000</code>) to use HTTP; a bare host uses HTTPS.
                  </p>
                </div>

                <div>
                  <label htmlFor="grafana-uid" className="block text-sm font-semibold text-gray-700 mb-1">
                    Dashboard UID
                  </label>
                  <input
                    id="grafana-uid"
                    type="text"
                    value={formUid}
                    onChange={(e) => setFormUid(e.target.value)}
                    placeholder="abc123def"
                    disabled={isSaving}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:bg-gray-50 disabled:text-gray-400"
                  />
                  <p className="text-xs text-gray-500 mt-1">
                    From the dashboard URL <code>/d/&lt;uid&gt;/&lt;slug&gt;</code> — this is the <code>&lt;uid&gt;</code> segment.
                  </p>
                </div>

                <div>
                  <label htmlFor="grafana-slug" className="block text-sm font-semibold text-gray-700 mb-1">
                    Dashboard Slug
                  </label>
                  <input
                    id="grafana-slug"
                    type="text"
                    value={formSlug}
                    onChange={(e) => setFormSlug(e.target.value)}
                    placeholder="my-dashboard"
                    disabled={isSaving}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:bg-gray-50 disabled:text-gray-400"
                  />
                  <p className="text-xs text-gray-500 mt-1">
                    The <code>&lt;slug&gt;</code> segment of the dashboard URL (the part after the UID).
                  </p>
                </div>

                {saveError && (
                  <div className="flex items-start gap-2 text-sm text-red-600 bg-red-50 border border-red-100 rounded-lg px-3 py-2">
                    <i className="fas fa-circle-exclamation mt-0.5"></i>
                    <span>{saveError}</span>
                  </div>
                )}

                <button
                  type="submit"
                  disabled={isSaving}
                  className="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-500 to-indigo-600 text-white rounded-xl font-medium hover:from-blue-600 hover:to-indigo-700 transition-all shadow-lg hover:shadow-xl disabled:opacity-60 disabled:cursor-not-allowed"
                >
                  {isSaving ? (
                    <>
                      <i className="fas fa-spinner fa-spin"></i>
                      Saving...
                    </>
                  ) : (
                    <>
                      <i className="fas fa-floppy-disk"></i>
                      Save &amp; Load Dashboard
                    </>
                  )}
                </button>
              </form>

              <button
                onClick={() => setIsHelpModalOpen(true)}
                className="text-sm text-blue-600 hover:text-blue-700 font-medium"
              >
                <i className="fas fa-book-open mr-2"></i>
                View Setup Guide
              </button>
            </div>
          </div>
        ) : grafanaConfig ? (
          /*
           * In demo mode the account's grafanaIp is play.grafana.org, which — like every real
           * Grafana — serves X-Frame-Options: deny, so the browser blocks the iframe
           * (ERR_BLOCKED_BY_RESPONSE / "refused to connect"). There is nothing to fix on the
           * embed; a public Grafana cannot be framed. So render a representative dashboard of our
           * own instead, and leave the "View in Grafana" topbar link — which opens in a new tab,
           * where framing rules do not apply — to reach the real board.
           */
          isDemoMode() ? (
            <DemoGrafanaDashboard accountName={grafanaConfig.accountName} />
          ) : (
            <iframe
              src={grafanaConfig.iframeSrc}
              title="Grafana Dashboard"
              className="w-full h-[calc(100vh-6rem)] border-0 rounded-xl shadow-sm grafana-iframe"
            />
          )
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
