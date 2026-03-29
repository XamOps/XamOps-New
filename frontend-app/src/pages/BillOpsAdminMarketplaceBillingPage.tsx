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
    <div style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>

      {/* ── Page Header ── */}
      <div style={{ marginBottom: '24px' }}>
        <h1 style={{ margin: 0, fontSize: '22px', fontWeight: 700, color: 'var(--text-primary, #111827)' }}>
          <i className="fas fa-history" style={{ marginRight: '10px', color: '#6366f1' }} />
          Historical Billing Data
        </h1>
        <p style={{ margin: '4px 0 0', color: 'var(--text-secondary, #6b7280)', fontSize: '14px' }}>
          Manage marketplace account billing snapshots (Dec 2024 – Mar 2026) for all linked accounts.
        </p>
      </div>

      {/* ── Error banner ── */}
      {error && (
        <div style={styles.alertDanger}>
          <i className="fas fa-exclamation-circle" style={{ marginRight: '8px' }} />
          {error}
        </div>
      )}

      {/* ── Connection Section ── */}
      <div style={styles.card}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <h2 style={styles.cardTitle}>
              <i className="fas fa-plug" style={{ marginRight: '8px', color: '#6366f1' }} />
              Marketplace Connection
            </h2>
            {accounts.length > 0 ? (
              <p style={{ margin: '4px 0 0', color: '#6b7280', fontSize: '13px' }}>
                <span style={styles.badgeGreen}>● Connected</span>
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
              <button style={styles.btnSecondary} onClick={loadAccounts}>
                <i className="fas fa-sync-alt" style={{ marginRight: '6px' }} />
                Re-sync
              </button>
            )}
            <button style={styles.btnPrimary} onClick={() => setShowConnectForm(v => !v)}>
              <i className={`fas ${showConnectForm ? 'fa-times' : 'fa-link'}`} style={{ marginRight: '6px' }} />
              {accounts.length > 0 ? 'Re-connect' : 'Connect Marketplace'}
            </button>
          </div>
        </div>

        {/* Connect form */}
        {showConnectForm && (
          <div style={{ marginTop: '20px', paddingTop: '20px', borderTop: '1px solid var(--border-color, #e5e7eb)' }}>
            <h3 style={{ margin: '0 0 16px', fontSize: '15px', fontWeight: 600 }}>
              Enter your Marketplace Payer Account details
            </h3>
            {connectError && (
              <div style={{ ...styles.alertDanger, marginBottom: '16px' }}>{connectError}</div>
            )}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
              <div>
                <label style={styles.label}>Account Name</label>
                <input
                  style={styles.input}
                  placeholder="e.g. My AWS Marketplace Account"
                  value={connectForm.accountName}
                  onChange={e => setConnectForm(f => ({ ...f, accountName: e.target.value }))}
                />
              </div>
              <div>
                <label style={styles.label}>External ID</label>
                <input
                  style={styles.input}
                  value={connectForm.externalId}
                  onChange={e => setConnectForm(f => ({ ...f, externalId: e.target.value }))}
                />
              </div>
            </div>
            <div style={{ marginTop: '12px' }}>
              <label style={styles.label}>
                IAM Role ARN&nbsp;
                <span style={{ color: '#9ca3af', fontWeight: 400 }}>
                  (created in your payer account)
                </span>
              </label>
              <input
                style={styles.input}
                placeholder="arn:aws:iam::123456789012:role/XamOpsMarketRole"
                value={connectForm.roleArn}
                onChange={e => setConnectForm(f => ({ ...f, roleArn: e.target.value }))}
              />
            </div>
            <div style={{ marginTop: '16px', display: 'flex', gap: '10px' }}>
              <button
                style={connecting ? styles.btnPrimaryDisabled : styles.btnPrimary}
                onClick={handleConnect}
                disabled={connecting}
              >
                {connecting ? (
                  <><i className="fas fa-spinner fa-spin" style={{ marginRight: '6px' }} />Connecting...</>
                ) : (
                  <><i className="fas fa-plug" style={{ marginRight: '6px' }} />Connect & Discover Accounts</>
                )}
              </button>
              <button style={styles.btnSecondary} onClick={() => setShowConnectForm(false)}>
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
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '16px', marginBottom: '20px' }}>
            <div style={styles.statCard}>
              <div style={styles.statValue}>{accounts.length}</div>
              <div style={styles.statLabel}>Total Accounts</div>
            </div>
            <div style={styles.statCard}>
              <div style={{ ...styles.statValue, color: '#6366f1' }}>{extractedCount}</div>
              <div style={styles.statLabel}>Data Extracted</div>
            </div>
            <div style={styles.statCard}>
              <div style={{ ...styles.statValue, color: '#f59e0b' }}>{accounts.length - extractedCount}</div>
              <div style={styles.statLabel}>Pending Extraction</div>
            </div>
          </div>

          {/* Extract banner */}
          <div style={{ ...styles.card, background: 'linear-gradient(135deg, #f5f3ff 0%, #ede9fe 100%)', border: '1px solid #c4b5fd', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
              <div>
                <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#4c1d95' }}>
                  <i className="fas fa-database" style={{ marginRight: '8px' }} />
                  Extract All Billing Data
                </h3>
                <p style={{ margin: '4px 0 0', color: '#6d28d9', fontSize: '13px' }}>
                  Pulls Dec 2024 – Mar 2026 from Cost Explorer for all {accounts.length} accounts.
                  Run this <strong>once before applying Billing Conductor</strong>.
                </p>
              </div>
              <button
                style={extracting ? styles.btnPurpleDisabled : styles.btnPurple}
                onClick={handleExtractAll}
                disabled={extracting}
              >
                {extracting ? (
                  <><i className="fas fa-spinner fa-spin" style={{ marginRight: '6px' }} />Extracting...</>
                ) : (
                  <><i className="fas fa-download" style={{ marginRight: '6px' }} />Extract All Billing Data</>
                )}
              </button>
            </div>

            {/* Extraction results */}
            {extractResult && (
              <div style={{ marginTop: '16px', paddingTop: '16px', borderTop: '1px solid #c4b5fd' }}>
                {Object.entries(extractResult).map(([accountId, result]) => (
                  <div key={accountId} style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '6px', fontSize: '13px' }}>
                    <span style={result.startsWith('SUCCESS') ? styles.badgeGreen : styles.badgeRed}>
                      {result.startsWith('SUCCESS') ? '✓' : '✗'}
                    </span>
                    <span style={{ fontFamily: 'monospace', color: '#374151' }}>{accountId}</span>
                    <span style={{ color: result.startsWith('SUCCESS') ? '#059669' : '#dc2626' }}>
                      {result.startsWith('SUCCESS') ? 'Saved to S3 + DB' : result.replace('ERROR:', '')}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* ── Accounts Table ── */}
          <div style={styles.card}>
            <h2 style={{ ...styles.cardTitle, marginBottom: '16px' }}>
              <i className="fas fa-list" style={{ marginRight: '8px', color: '#6366f1' }} />
              Marketplace Accounts
            </h2>

            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#9ca3af' }}>
                <i className="fas fa-spinner fa-spin" style={{ fontSize: '24px' }} />
                <p>Loading accounts...</p>
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={styles.table}>
                  <thead>
                    <tr style={styles.thead}>
                      <th style={styles.th}>AWS Account ID</th>
                      <th style={styles.th}>Account Name</th>
                      <th style={styles.th}>Category</th>
                      <th style={styles.th}>Contact Email</th>
                      <th style={styles.th}>Data Status</th>
                      <th style={styles.th}>Discovered</th>
                      <th style={styles.th}>Share Billing</th>
                    </tr>
                  </thead>
                  <tbody>
                    {accounts.map(account => (
                      <tr key={account.id} style={styles.tr}>
                        <td style={styles.td}>
                          <span style={{ fontFamily: 'monospace', fontSize: '13px', color: '#374151' }}>
                            {account.awsAccountId}
                          </span>
                        </td>
                        <td style={styles.td}>
                          <span style={{ fontWeight: 600, color: 'var(--text-primary, #111827)' }}>
                            {account.accountName}
                          </span>
                        </td>
                        <td style={styles.td}>
                          {account.category === 'MARKETPLACE_XAMOPS' ? (
                            <span style={styles.badgePurple}>MP + XamOps</span>
                          ) : (
                            <span style={styles.badgeGray}>MP Only</span>
                          )}
                        </td>
                        <td style={styles.td}>
                          <span style={{ color: '#6b7280', fontSize: '13px' }}>
                            {account.contactEmail || '—'}
                          </span>
                        </td>
                        <td style={styles.td}>
                          {account.billingExtracted ? (
                            <span style={styles.badgeGreen}>
                              <i className="fas fa-check-circle" style={{ marginRight: '4px' }} />
                              Extracted
                            </span>
                          ) : (
                            <span style={styles.badgeOrange}>
                              <i className="fas fa-clock" style={{ marginRight: '4px' }} />
                              Pending
                            </span>
                          )}
                        </td>
                        <td style={styles.td}>
                          <span style={{ color: '#9ca3af', fontSize: '12px' }}>
                            {formatDate(account.discoveredAt)}
                          </span>
                        </td>
                        <td style={styles.td}>
                          <button
                            style={styles.btnShare}
                            onClick={() => openShareModal(account)}
                          >
                            <i className="fas fa-paper-plane" style={{ marginRight: '5px' }} />
                            Send Link
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {accounts.length === 0 && (
                  <div style={{ textAlign: 'center', padding: '40px', color: '#9ca3af' }}>
                    <i className="fas fa-inbox" style={{ fontSize: '32px', marginBottom: '12px' }} />
                    <p>No accounts found. Connect a marketplace account above.</p>
                  </div>
                )}
              </div>
            )}
          </div>
        </>
      )}

      {/* ── Share Modal ── */}
      {shareModal.open && (
        <div style={styles.modalOverlay} onClick={() => setShareModal(m => ({ ...m, open: false }))}>
          <div style={styles.modal} onClick={e => e.stopPropagation()}>
            <div style={styles.modalHeader}>
              <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 700 }}>
                <i className="fas fa-share-alt" style={{ marginRight: '8px', color: '#6366f1' }} />
                Share Billing Data
              </h3>
              <button
                style={styles.modalClose}
                onClick={() => setShareModal(m => ({ ...m, open: false }))}
              >
                <i className="fas fa-times" />
              </button>
            </div>

            <div style={styles.modalBody}>
              {/* Account info */}
              <div style={{ background: '#f9fafb', border: '1px solid #e5e7eb', borderRadius: '6px', padding: '12px 16px', marginBottom: '20px' }}>
                <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Account</div>
                <div style={{ fontWeight: 700, color: '#111827', marginTop: '4px' }}>{shareModal.accountName}</div>
                <div style={{ fontFamily: 'monospace', color: '#6b7280', fontSize: '13px' }}>{shareModal.awsAccountId}</div>
              </div>

              {/* Month picker */}
              <div style={{ marginBottom: '20px' }}>
                <label style={styles.label}>
                  Billing Month
                  {loadingMonths && (
                    <i className="fas fa-spinner fa-spin" style={{ marginLeft: '8px', color: '#9ca3af' }} />
                  )}
                </label>
                {availableMonths.length > 0 ? (
                  <select
                    style={{ ...styles.input, cursor: 'pointer' }}
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
                    style={styles.input}
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
                <div style={{ ...styles.alertSuccess, textAlign: 'center' }}>
                  <i className="fas fa-check-circle" style={{ marginRight: '8px' }} />
                  {sendSuccess}
                </div>
              ) : (
                <>
                  <label style={styles.label}>Recipient Email Address</label>
                  <input
                    style={styles.input}
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
                    <div style={{ ...styles.alertDanger, marginTop: '12px' }}>{sendError}</div>
                  )}
                </>
              )}
            </div>

            {!sendSuccess && (
              <div style={styles.modalFooter}>
                <button
                  style={styles.btnSecondary}
                  onClick={() => setShareModal(m => ({ ...m, open: false }))}
                >
                  Cancel
                </button>
                <button
                  style={sending ? styles.btnPrimaryDisabled : styles.btnPrimary}
                  onClick={handleSendBilling}
                  disabled={sending}
                >
                  {sending ? (
                    <><i className="fas fa-spinner fa-spin" style={{ marginRight: '6px' }} />Sending...</>
                  ) : (
                    <><i className="fas fa-paper-plane" style={{ marginRight: '6px' }} />Send Download Link</>
                  )}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles: Record<string, React.CSSProperties> = {
  card: {
    background: 'var(--card-bg, #ffffff)',
    border: '1px solid var(--border-color, #e5e7eb)',
    borderRadius: '10px',
    padding: '20px 24px',
    marginBottom: '20px',
    boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
  },
  cardTitle: {
    margin: '0 0 4px',
    fontSize: '16px',
    fontWeight: 700,
    color: 'var(--text-primary, #111827)',
  },
  statCard: {
    background: 'var(--card-bg, #ffffff)',
    border: '1px solid var(--border-color, #e5e7eb)',
    borderRadius: '10px',
    padding: '20px 24px',
    textAlign: 'center',
    boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
  },
  statValue: {
    fontSize: '28px',
    fontWeight: 800,
    color: '#111827',
    lineHeight: 1,
  },
  statLabel: {
    marginTop: '6px',
    fontSize: '13px',
    color: '#6b7280',
    fontWeight: 500,
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse' as const,
    fontSize: '14px',
  },
  thead: {
    background: 'var(--table-header-bg, #f9fafb)',
  },
  th: {
    padding: '10px 14px',
    textAlign: 'left' as const,
    fontWeight: 600,
    fontSize: '12px',
    color: '#6b7280',
    textTransform: 'uppercase' as const,
    letterSpacing: '0.05em',
    borderBottom: '1px solid var(--border-color, #e5e7eb)',
    whiteSpace: 'nowrap' as const,
  },
  tr: {
    borderBottom: '1px solid var(--border-color, #f3f4f6)',
  },
  td: {
    padding: '12px 14px',
    verticalAlign: 'middle' as const,
  },
  label: {
    display: 'block',
    marginBottom: '6px',
    fontSize: '13px',
    fontWeight: 600,
    color: 'var(--text-primary, #374151)',
  },
  input: {
    width: '100%',
    padding: '9px 12px',
    border: '1px solid var(--border-color, #d1d5db)',
    borderRadius: '6px',
    fontSize: '14px',
    color: 'var(--text-primary, #111827)',
    background: 'var(--input-bg, #ffffff)',
    boxSizing: 'border-box' as const,
    outline: 'none',
  },
  btnPrimary: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '9px 18px',
    background: '#6366f1',
    color: '#ffffff',
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'pointer',
    whiteSpace: 'nowrap' as const,
  },
  btnPrimaryDisabled: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '9px 18px',
    background: '#a5b4fc',
    color: '#ffffff',
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'not-allowed',
    whiteSpace: 'nowrap' as const,
  },
  btnSecondary: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '9px 18px',
    background: 'transparent',
    color: 'var(--text-primary, #374151)',
    border: '1px solid var(--border-color, #d1d5db)',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'pointer',
    whiteSpace: 'nowrap' as const,
  },
  btnPurple: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '9px 18px',
    background: '#7c3aed',
    color: '#ffffff',
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'pointer',
    whiteSpace: 'nowrap' as const,
  },
  btnPurpleDisabled: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '9px 18px',
    background: '#c4b5fd',
    color: '#ffffff',
    border: 'none',
    borderRadius: '6px',
    fontSize: '14px',
    fontWeight: 600,
    cursor: 'not-allowed',
    whiteSpace: 'nowrap' as const,
  },
  btnShare: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '6px 14px',
    background: 'transparent',
    color: '#6366f1',
    border: '1px solid #6366f1',
    borderRadius: '5px',
    fontSize: '13px',
    fontWeight: 600,
    cursor: 'pointer',
    whiteSpace: 'nowrap' as const,
  },
  badgeGreen: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '2px 8px',
    background: '#d1fae5',
    color: '#065f46',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 600,
  },
  badgeRed: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '2px 8px',
    background: '#fee2e2',
    color: '#991b1b',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 600,
  },
  badgeOrange: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '2px 8px',
    background: '#fef3c7',
    color: '#92400e',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 600,
  },
  badgePurple: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '2px 8px',
    background: '#ede9fe',
    color: '#5b21b6',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 600,
  },
  badgeGray: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '2px 8px',
    background: '#f3f4f6',
    color: '#374151',
    borderRadius: '999px',
    fontSize: '12px',
    fontWeight: 600,
  },
  alertDanger: {
    padding: '10px 14px',
    background: '#fee2e2',
    border: '1px solid #fca5a5',
    borderRadius: '6px',
    color: '#991b1b',
    fontSize: '13px',
  },
  alertSuccess: {
    padding: '10px 14px',
    background: '#d1fae5',
    border: '1px solid #6ee7b7',
    borderRadius: '6px',
    color: '#065f46',
    fontSize: '13px',
  },
  modalOverlay: {
    position: 'fixed' as const,
    inset: 0,
    background: 'rgba(0,0,0,0.5)',
    zIndex: 1000,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '20px',
  },
  modal: {
    background: 'var(--card-bg, #ffffff)',
    borderRadius: '12px',
    width: '100%',
    maxWidth: '480px',
    boxShadow: '0 20px 60px rgba(0,0,0,0.2)',
    overflow: 'hidden',
  },
  modalHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '16px 20px',
    borderBottom: '1px solid var(--border-color, #e5e7eb)',
  },
  modalBody: {
    padding: '20px',
  },
  modalFooter: {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '10px',
    padding: '16px 20px',
    borderTop: '1px solid var(--border-color, #e5e7eb)',
  },
  modalClose: {
    background: 'none',
    border: 'none',
    cursor: 'pointer',
    color: '#6b7280',
    fontSize: '16px',
    padding: '4px',
  },
};

export default BillOpsAdminMarketplaceBillingPage;
