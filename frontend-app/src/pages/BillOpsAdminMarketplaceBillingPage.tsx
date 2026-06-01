/**
 * BillOps Admin — Historical Billing Data
 *
 * Allows BILLOPS_ADMIN to:
 *   1. Connect a marketplace payer account (one-time, via IAM Role ARN)
 *   2. Auto-discover all member accounts and categorise them
 *   3. Extract billing data Dec 2024 – Mar 2026 for all accounts → S3 + DB
 *   4. On-demand: share a 48-hour billing CSV download link via email
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';

// ─── Types ────────────────────────────────────────────────────────────────────

interface PayerInfo {
  payerAccountId: number;
  payerAwsAccountId: string;
  accountName: string;
  totalDiscovered: number;
  marketplaceXamops: number;
  marketplaceOnly: number;
  status: string;
}

interface MarketplaceAccount {
  id: number;
  awsAccountId: string;
  accountName: string;
  contactEmail: string | null;
  category: 'MARKETPLACE_XAMOPS' | 'MARKETPLACE_ONLY';
  billingExtracted: boolean;
  status: string;
  discoveredAt: string;
}

interface ConnectForm {
  accountName: string;
  roleArn: string;
  externalId: string;
}

interface ShareModal {
  open: boolean;
  awsAccountId: string;
  accountName: string;
  email: string;
  yearMonth: string;
}

// ─── Component ────────────────────────────────────────────────────────────────

const BillOpsAdminMarketplaceBillingPage: React.FC = () => {
  // Connection state
  const [payerInfo, setPayerInfo] = useState<PayerInfo | null>(null);
  const [accounts, setAccounts] = useState<MarketplaceAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Connect form
  const [showConnectForm, setShowConnectForm] = useState(false);
  const [connectForm, setConnectForm] = useState<ConnectForm>({
    accountName: '',
    roleArn: '',
    externalId: 'xamops-marketplace-sync',
  });
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  // Extraction
  const [extracting, setExtracting] = useState(false);
  const [extractResult, setExtractResult] = useState<Record<string, string> | null>(null);

  // Share modal
  const [shareModal, setShareModal] = useState<ShareModal>({
    open: false,
    awsAccountId: '',
    accountName: '',
    email: '',
    yearMonth: '',
  });
  const [availableMonths, setAvailableMonths] = useState<string[]>([]);
  const [loadingMonths, setLoadingMonths] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendSuccess, setSendSuccess] = useState<string | null>(null);
  const [sendError, setSendError] = useState<string | null>(null);

  // ─── Data loading ──────────────────────────────────────────────────────────

  const loadAccounts = useCallback(async () => {
    try {
      const [accountsRes, payersRes] = await Promise.all([
        fetch('/api/xamops/billops-admin/marketplace/accounts', { credentials: 'include' }),
        fetch('/api/xamops/billops-admin/marketplace/payers', { credentials: 'include' }),
      ]);
      if (!accountsRes.ok) throw new Error('Failed to load marketplace accounts');
      const data: MarketplaceAccount[] = await accountsRes.json();
      setAccounts(data);
      if (payersRes.ok) {
        const payers: PayerInfo[] = await payersRes.json();
        if (payers.length > 0) setPayerInfo(payers[0]);
      }
    } catch (e: any) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    loadAccounts().finally(() => setLoading(false));
  }, [loadAccounts]);

  // ─── Connect marketplace ───────────────────────────────────────────────────

  const handleConnect = async () => {
    if (!connectForm.accountName.trim() || !connectForm.roleArn.trim()) {
      setConnectError('Account name and Role ARN are required.');
      return;
    }
    setConnecting(true);
    setConnectError(null);
    try {
      const res = await fetch('/api/xamops/billops-admin/marketplace/connect', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...connectForm,
          roleArn: connectForm.roleArn.replace(/\s+/g, ''),  // strip any accidental whitespace
          externalId: connectForm.externalId.trim(),
        }),
      });
      if (!res.ok) {
        const err = await res.text();
        throw new Error(err || 'Failed to connect marketplace account');
      }
      const data: PayerInfo = await res.json();
      setPayerInfo(data);
      setShowConnectForm(false);
      await loadAccounts();
    } catch (e: any) {
      setConnectError(e.message);
    } finally {
      setConnecting(false);
    }
  };

  // ─── Extract all billing ───────────────────────────────────────────────────

  const handleExtractAll = async () => {
    if (!payerInfo) return;
    setExtracting(true);
    setExtractResult(null);
    try {
      const res = await fetch(
        `/api/xamops/billops-admin/marketplace/extract-all/${payerInfo.payerAccountId}`,
        { method: 'POST', credentials: 'include' }
      );
      if (!res.ok) throw new Error('Extraction failed');
      const data: Record<string, string> = await res.json();
      setExtractResult(data);
      await loadAccounts(); // refresh extracted flags
    } catch (e: any) {
      setExtractResult({ error: e.message });
    } finally {
      setExtracting(false);
    }
  };

  // ─── Share billing ─────────────────────────────────────────────────────────

  const openShareModal = async (account: MarketplaceAccount) => {
    setSendSuccess(null);
    setSendError(null);
    setAvailableMonths([]);
    setShareModal({
      open: true,
      awsAccountId: account.awsAccountId,
      accountName: account.accountName,
      email: account.contactEmail || '',
      yearMonth: '',
    });

    // Load available months for this account
    setLoadingMonths(true);
    try {
      const res = await fetch(
        `/api/xamops/billops-admin/marketplace/accounts/${account.awsAccountId}/billing-months`,
        { credentials: 'include' }
      );
      if (res.ok) {
        const months: string[] = await res.json();
        setAvailableMonths(months);
        if (months.length > 0) {
          setShareModal(m => ({ ...m, yearMonth: months[months.length - 1] }));
        }
      }
    } catch {
      // non-fatal — user can still type a month
    } finally {
      setLoadingMonths(false);
    }
  };

  const handleSendBilling = async () => {
    if (!shareModal.email.trim()) {
      setSendError('Please enter a recipient email address.');
      return;
    }
    if (!shareModal.yearMonth) {
      setSendError('Please select a billing month.');
      return;
    }
    setSending(true);
    setSendError(null);
    try {
      const res = await fetch('/api/xamops/billops-admin/marketplace/share-billing', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          awsAccountId: shareModal.awsAccountId,
          recipientEmail: shareModal.email,
          yearMonth: shareModal.yearMonth,
        }),
      });
      if (!res.ok) {
        const err = await res.text();
        throw new Error(err || 'Failed to send billing data');
      }
      setSendSuccess(`Download link sent to ${shareModal.email}`);
      setTimeout(() => setShareModal(m => ({ ...m, open: false })), 2000);
    } catch (e: any) {
      setSendError(e.message);
    } finally {
      setSending(false);
    }
  };

  // ─── Helpers ───────────────────────────────────────────────────────────────

  const extractedCount = accounts.filter(a => a.billingExtracted).length;
  const xamopsCount    = accounts.filter(a => a.category === 'MARKETPLACE_XAMOPS').length;
  const onlyCount      = accounts.filter(a => a.category === 'MARKETPLACE_ONLY').length;

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });

  // ─── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <div className="max-w-5xl space-y-5">

        {/* ── Page Header ── */}
        <div className="mb-5">
          <div className="flex items-center gap-2.5 mb-1">
            <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
              <Icon icon="mdi:history" width={18} className="text-indigo-600" />
            </div>
            <h1 className="text-[18px] font-semibold text-[rgba(0,0,0,0.88)]">Historical Billing Data</h1>
          </div>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] ml-11.5">
            Manage marketplace account billing snapshots (Dec 2024 – Mar 2026) for all linked accounts.
          </p>
        </div>

        {/* ── Error banner ── */}
        {error && (
          <div className="flex items-center gap-2 px-4 py-3 bg-red-50 border border-red-200 rounded-xl text-red-700 text-[13px] mb-4">
            <Icon icon="mdi:alert-circle-outline" width={16} className="flex-shrink-0" />
            {error}
          </div>
        )}

        {/* ── Connection Section ── */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-5">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
            <div>
              <h2 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-1 flex items-center gap-2">
                <Icon icon="mdi:power-plug-outline" width={16} className="text-indigo-600" />
                Marketplace Connection
              </h2>
              {accounts.length > 0 ? (
                <p style={{ margin: '4px 0 0', color: '#6b7280', fontSize: '13px' }}>
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-full text-[11px] font-semibold">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                    Connected
                  </span>
                  &nbsp;&nbsp;{accounts.length} accounts discovered
                  &nbsp;·&nbsp; {xamopsCount} on XamOps
                  &nbsp;·&nbsp; {onlyCount} marketplace-only
                </p>
              ) : (
                <p style={{ margin: '4px 0 0', color: '#9ca3af', fontSize: '13px' }}>
                  No marketplace account connected yet.
                </p>
              )}
            </div>
            <div style={{ display: 'flex', gap: '10px' }}>
              {accounts.length > 0 && (
                <button className="flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.7)] text-[13px] font-semibold rounded-lg hover:bg-[rgba(0,0,0,0.025)] transition-all" onClick={loadAccounts}>
                  <Icon icon="mdi:refresh" width={14} />
                  Re-sync
                </button>
              )}
              <button className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-lg transition-all" onClick={() => setShowConnectForm(v => !v)}>
                <Icon icon={showConnectForm ? 'mdi:close' : 'mdi:link-variant'} width={14} />
                {accounts.length > 0 ? 'Re-connect' : 'Connect Marketplace'}
              </button>
            </div>
          </div>

          {/* Connect form */}
          {showConnectForm && (
            <div className="mt-5 pt-5 border-t border-[rgba(0,0,0,0.06)]">
              <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-4">Enter your Marketplace Payer Account details</h3>
              {connectError && (
                <div className="flex items-center gap-2 px-4 py-3 bg-red-50 border border-red-200 rounded-xl text-red-700 text-[13px] mb-4">
                  <Icon icon="mdi:alert-circle-outline" width={16} className="flex-shrink-0" />
                  {connectError}
                </div>
              )}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                <div>
                  <label className="block mb-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">Account Name</label>
                  <input
                    className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    placeholder="e.g. My AWS Marketplace Account"
                    value={connectForm.accountName}
                    onChange={e => setConnectForm(f => ({ ...f, accountName: e.target.value }))}
                  />
                </div>
                <div>
                  <label className="block mb-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">External ID</label>
                  <input
                    className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    value={connectForm.externalId}
                    onChange={e => setConnectForm(f => ({ ...f, externalId: e.target.value }))}
                  />
                </div>
              </div>
              <div style={{ marginTop: '12px' }}>
                <label className="block mb-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">
                  IAM Role ARN&nbsp;
                  <span style={{ color: '#9ca3af', fontWeight: 400 }}>
                    (created in your payer account)
                  </span>
                </label>
                <input
                  className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  placeholder="arn:aws:iam::123456789012:role/XamOpsMarketRole"
                  value={connectForm.roleArn}
                  onChange={e => setConnectForm(f => ({ ...f, roleArn: e.target.value }))}
                />
              </div>
              <div style={{ marginTop: '16px', display: 'flex', gap: '10px' }}>
                <button
                  className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  onClick={handleConnect}
                  disabled={connecting}
                >
                  {connecting ? (
                    <><Icon icon="mdi:loading" width={14} className="animate-spin" />Connecting...</>
                  ) : (
                    <><Icon icon="mdi:power-plug-outline" width={14} />Connect & Discover Accounts</>
                  )}
                </button>
                <button className="flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.7)] text-[13px] font-semibold rounded-lg hover:bg-[rgba(0,0,0,0.025)] transition-all" onClick={() => setShowConnectForm(false)}>
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>

        {/* ── Stats + Extract Section ── */}
        {accounts.length > 0 && (
          <>
            {/* Summary cards */}
            <div className="grid grid-cols-3 gap-4 mb-5">
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 text-center">
                <p className="tabular-nums text-[rgba(0,0,0,0.88)]" style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px' }}>{accounts.length}</p>
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.5)] mt-2">Total Accounts</p>
              </div>
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 text-center">
                <p className="tabular-nums text-indigo-600" style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px' }}>{extractedCount}</p>
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.5)] mt-2">Data Extracted</p>
              </div>
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 text-center">
                <p className="tabular-nums text-amber-500" style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px' }}>{accounts.length - extractedCount}</p>
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.5)] mt-2">Pending Extraction</p>
              </div>
            </div>

            {/* Extract banner */}
            <div className="bg-indigo-50 border border-indigo-200 rounded-xl p-5 mb-5 flex items-center justify-between flex-wrap gap-3">
              <div>
                <h3 className="text-[15px] font-semibold text-indigo-900 flex items-center gap-2">
                  <Icon icon="mdi:database-outline" width={16} className="text-indigo-600" />
                  Extract All Billing Data
                </h3>
                <p className="text-[13px] text-indigo-700 mt-1">
                  Pulls Dec 2024 – Mar 2026 from Cost Explorer for all {accounts.length} accounts.
                  Run this <strong>once before applying Billing Conductor</strong>.
                </p>
              </div>
              <button
                onClick={handleExtractAll}
                disabled={extracting}
                className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Icon icon={extracting ? 'mdi:loading' : 'mdi:download'} width={14} className={extracting ? 'animate-spin' : ''} />
                {extracting ? 'Extracting...' : 'Extract All Billing Data'}
              </button>

              {/* Extraction results */}
              {extractResult && (
                <div className="mt-4 pt-4 border-t border-indigo-200 space-y-2">
                  {Object.entries(extractResult).map(([accountId, result]) => (
                    <div key={accountId} className="flex items-center gap-2.5 text-[13px]">
                      <span className={result.startsWith('SUCCESS')
                        ? 'text-emerald-600 font-bold'
                        : 'text-red-600 font-bold'}>
                        {result.startsWith('SUCCESS') ? '✓' : '✗'}
                      </span>
                      <span className="font-mono text-[rgba(0,0,0,0.7)]">{accountId}</span>
                      <span className={result.startsWith('SUCCESS') ? 'text-emerald-700' : 'text-red-600'}>
                        {result.startsWith('SUCCESS') ? 'Saved to S3 + DB' : result.replace('ERROR:', '')}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* ── Accounts Table ── */}
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 mb-5">
              <h2 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-1 flex items-center gap-2" style={{ marginBottom: '16px' }}>
                <Icon icon="mdi:format-list-bulleted" width={16} className="text-indigo-600" />
                Marketplace Accounts
              </h2>

              {loading ? (
                <div className="flex items-center justify-center gap-2 py-16 text-[rgba(0,0,0,0.4)]">
                  <Icon icon="mdi:loading" width={24} className="animate-spin" />
                  <span className="text-[13px]">Loading accounts...</span>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">AWS Account ID</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Account Name</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Category</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Contact Email</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Data Status</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Discovered</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] whitespace-nowrap">Share Billing</th>
                      </tr>
                    </thead>
                    <tbody>
                      {accounts.map(account => (
                        <tr key={account.id} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                          <td className="px-4 py-3 align-middle">
                            <span style={{ fontFamily: 'monospace', fontSize: '13px', color: '#374151' }}>
                              {account.awsAccountId}
                            </span>
                          </td>
                          <td className="px-4 py-3 align-middle">
                            <span style={{ fontWeight: 600, color: 'var(--text-primary, #111827)' }}>
                              {account.accountName}
                            </span>
                          </td>
                          <td className="px-4 py-3 align-middle">
                            {account.category === 'MARKETPLACE_XAMOPS' ? (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-indigo-50 text-indigo-700 border border-indigo-200 rounded-full text-[11px] font-semibold">MP + XamOps</span>
                            ) : (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.1)] rounded-full text-[11px] font-semibold">MP Only</span>
                            )}
                          </td>
                          <td className="px-4 py-3 align-middle">
                            <span style={{ color: '#6b7280', fontSize: '13px' }}>
                              {account.contactEmail || '—'}
                            </span>
                          </td>
                          <td className="px-4 py-3 align-middle">
                            {account.billingExtracted ? (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-full text-[11px] font-semibold">
                                <Icon icon="mdi:check-circle-outline" width={11} />
                                Extracted
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-amber-50 text-amber-700 border border-amber-200 rounded-full text-[11px] font-semibold">
                                <Icon icon="mdi:clock-outline" width={11} />
                                Pending
                              </span>
                            )}
                          </td>
                          <td className="px-4 py-3 align-middle">
                            <span style={{ color: '#9ca3af', fontSize: '12px' }}>
                              {formatDate(account.discoveredAt)}
                            </span>
                          </td>
                          <td className="px-4 py-3 align-middle">
                            <button
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 border border-indigo-300 text-indigo-600 text-[12px] font-semibold rounded-lg hover:bg-indigo-50 transition-all"
                              onClick={() => openShareModal(account)}
                            >
                              <Icon icon="mdi:send-outline" width={12} />
                              Send Link
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>

                  {accounts.length === 0 && (
                    <div className="py-16 text-center">
                      <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mx-auto mb-3">
                        <Icon icon="mdi:inbox-outline" width={28} className="text-[rgba(0,0,0,0.25)]" />
                      </div>
                      <p className="text-[13px] text-[rgba(0,0,0,0.45)]">No accounts found. Connect a marketplace account above.</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}

        {/* ── Share Modal ── */}
        {shareModal.open && (
          <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-5" onClick={() => setShareModal(m => ({ ...m, open: false }))}>
            <div className="bg-white rounded-xl w-full max-w-md shadow-2xl overflow-hidden" onClick={e => e.stopPropagation()}>
              <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
                <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                  <Icon icon="mdi:share-variant-outline" width={16} className="text-indigo-600" />
                  Share Billing Data
                </h3>
                <button
                  className="p-1 rounded-lg text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                  onClick={() => setShareModal(m => ({ ...m, open: false }))}
                >
                  <Icon icon="mdi:close" width={16} />
                </button>
              </div>

              <div className="p-5">
                {/* Account info */}
                <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg px-4 py-3 mb-5">
                  <div className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.4)] mb-1">Account</div>
                  <div className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{shareModal.accountName}</div>
                  <div className="font-mono text-[13px] text-[rgba(0,0,0,0.5)]">{shareModal.awsAccountId}</div>
                </div>

                {/* Month picker */}
                <div style={{ marginBottom: '20px' }}>
                  <label className="block mb-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">
                    Billing Month
                    {loadingMonths && (
                      <Icon icon="mdi:loading" width={14} className="animate-spin ml-2 text-[rgba(0,0,0,0.4)] inline" />
                    )}
                  </label>
                  {availableMonths.length > 0 ? (
                    <select
                      className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 cursor-pointer"
                      value={shareModal.yearMonth}
                      onChange={e => setShareModal(m => ({ ...m, yearMonth: e.target.value }))}
                    >
                      <option value="">— Select a month —</option>
                      {availableMonths.map(ym => (
                        <option key={ym} value={ym}>{ym}</option>
                      ))}
                    </select>
                  ) : (
                    <input
                      className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                      placeholder="e.g. 2025-02"
                      value={shareModal.yearMonth}
                      onChange={e => setShareModal(m => ({ ...m, yearMonth: e.target.value }))}
                    />
                  )}
                  <p style={{ margin: '4px 0 0', color: '#9ca3af', fontSize: '12px' }}>
                    {availableMonths.length > 0
                      ? `${availableMonths.length} month(s) available — most recent pre-selected.`
                      : 'No extracted data yet. Enter a month or run Extract All first.'}
                  </p>
                </div>

                {sendSuccess ? (
                  <div className="flex items-center gap-2 px-3 py-2 bg-emerald-50 border border-emerald-200 rounded-lg text-emerald-700 text-[13px] text-center">
                    <Icon icon="mdi:check-circle-outline" width={14} />
                    {sendSuccess}
                  </div>
                ) : (
                  <>
                    <label className="block mb-1.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">Recipient Email Address</label>
                    <input
                      className="w-full px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                      type="email"
                      placeholder="customer@example.com"
                      value={shareModal.email}
                      onChange={e => setShareModal(m => ({ ...m, email: e.target.value }))}
                      onKeyDown={e => e.key === 'Enter' && handleSendBilling()}
                    />
                    <p style={{ margin: '6px 0 0', color: '#9ca3af', fontSize: '12px' }}>
                      A CSV download link (valid 48 hours) will be emailed to this address.
                    </p>

                    {sendError && (
                      <div className="flex items-center gap-2 px-3 py-2 bg-red-50 border border-red-200 rounded-lg text-red-700 text-[13px]" style={{ marginTop: '12px' }}>
                        <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0" />
                        {sendError}
                      </div>
                    )}
                  </>
                )}
              </div>

              {!sendSuccess && (
                <div className="flex justify-end gap-3 px-5 py-4 border-t border-[rgba(0,0,0,0.06)]">
                  <button
                    className="flex items-center gap-2 px-4 py-2 border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.7)] text-[13px] font-semibold rounded-lg hover:bg-[rgba(0,0,0,0.025)] transition-all"
                    onClick={() => setShareModal(m => ({ ...m, open: false }))}
                  >
                    Cancel
                  </button>
                  <button
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    onClick={handleSendBilling}
                    disabled={sending}
                  >
                    {sending ? (
                      <><Icon icon="mdi:loading" width={14} className="animate-spin" />Sending...</>
                    ) : (
                      <><Icon icon="mdi:send-outline" width={14} />Send Download Link</>
                    )}
                  </button>
                </div>
              )}
            </div>
          </div>
        )}

      </div>
    </div>
  );
};

export default BillOpsAdminMarketplaceBillingPage;
