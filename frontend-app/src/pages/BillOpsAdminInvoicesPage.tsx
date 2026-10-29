/**
 * BillOps Admin — Invoices
 *
 * Every invoice across every account, laid out like the AWS Billing console's lists:
 * a summary strip, one table with its filters above it, and a single primary action.
 *
 * - Stat tiles summarise the account in view and double as status filters.
 * - Search, account, status and period filters narrow the table; columns sort.
 * - "Create draft" opens a dialog. AWS drafts are read from the Cost and Usage Report;
 *   when it cannot cover the month the dialog says why, and Cost Explorer is only
 *   reached if the admin chooses it there.
 * - Two compatible drafts (same account and period) can be merged from the selection bar.
 *
 * For: ROLE_BILLOPS_ADMIN
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useBillOpsAdminInvoices from '../hooks/useBillOpsAdminInvoices';
import { useTopbar } from '../contexts/TopbarContext';
import type {
  AdminInvoiceItem,
  CloudAccount,
  CurUnavailableNotice,
  GenerationParams,
  ClientOption,
  UploadDraftParams,
  UploadDraftPreview,
  UploadResult,
} from '../types/billopsAdminInvoices';
import { AlertDialog, DialogPanel, DialogRoot } from '../components/ui';
import '../styles/billopsInvoiceUi.css';

// ─── Formatting ──────────────────────────────────────────────────────────────

const usd = (value: number | null | undefined): string =>
  new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value ?? 0);

/** Stat-tile money: compact at scale ("$12.9K"), exact below it. */
const usdCompact = (value: number): string =>
  Math.abs(value) >= 10_000
    ? new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
        notation: 'compact',
        maximumFractionDigits: 1,
      }).format(value)
    : usd(value);

/** "2026-09" → "Sep 2026" (or "September 2026" with {@code long}). */
const formatPeriod = (period: string | null | undefined, long = false): string => {
  if (!period) return '—';
  const [y, m] = period.split('-').map(Number);
  if (!y || !m) return period;
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: long ? 'long' : 'short', year: 'numeric' });
};

/** The last 24 billing periods, newest first — the months a draft can reasonably be for. */
const periodOptions = (): { value: string; label: string }[] => {
  const out: { value: string; label: string }[] = [];
  const d = new Date();
  d.setDate(1);
  for (let i = 0; i < 24; i++) {
    const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    out.push({ value, label: formatPeriod(value, true) + (i === 0 ? ' (month to date)' : '') });
    d.setMonth(d.getMonth() - 1);
  }
  return out;
};

const accountIdOf = (invoice: AdminInvoiceItem): string => {
  if (invoice.awsAccountId && invoice.awsAccountId !== 'N/A') return invoice.awsAccountId;
  if (invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A') return invoice.gcpProjectId;
  return invoice.azureSubscriptionId || '';
};

// ─── Small pieces ────────────────────────────────────────────────────────────

const StatusPill: React.FC<{ status: AdminInvoiceItem['status'] }> = ({ status }) => {
  const cls = status === 'FINALIZED' ? 'inv-pill--finalized' : status === 'DRAFT' ? 'inv-pill--draft' : 'inv-pill--void';
  const icon = status === 'FINALIZED' ? 'fa-check' : status === 'DRAFT' ? 'fa-pen' : 'fa-ban';
  const label = status === 'FINALIZED' ? 'Finalized' : status === 'DRAFT' ? 'Draft' : 'Void';
  return (
    <span className={`inv-pill ${cls}`}>
      <i className={`fas ${icon}`} aria-hidden="true" />
      {label}
    </span>
  );
};

const SourcePills: React.FC<{ invoice: Pick<AdminInvoiceItem, 'dataSource' | 'provisional' | 'uploadedFileName'> }> = ({ invoice }) => (
  <span className="inline-flex flex-wrap gap-1">
    {invoice.uploadedFileName && (
      <span className="inv-pill inv-pill--upload" title={`Created from uploaded bill: ${invoice.uploadedFileName}`}>
        Uploaded
      </span>
    )}
    {invoice.dataSource === 'CUR' && (
      <span className="inv-pill inv-pill--cur" title="Built from the Cost and Usage Report">CUR</span>
    )}
    {invoice.dataSource === 'COST_EXPLORER' && (
      <span className="inv-pill inv-pill--ce" title="Built from Cost Explorer">Cost Explorer</span>
    )}
    {invoice.provisional && (
      <span
        className="inv-pill inv-pill--provisional"
        title="The month had not closed when this was built. AWS may still change these figures."
      >
        Provisional
      </span>
    )}
    {!invoice.dataSource && !invoice.provisional && !invoice.uploadedFileName && <span className="inv-muted">—</span>}
  </span>
);

type StatusFilter = 'ALL' | 'DRAFT' | 'FINALIZED' | 'VOID';
type SortKey = 'newest' | 'account' | 'period' | 'total';
interface SortState { key: SortKey; dir: 'asc' | 'desc' }

const PAGE_SIZE = 20;

// ─── Create draft dialog ─────────────────────────────────────────────────────

interface CreateDraftDialogProps {
  isOpen: boolean;
  onClose: () => void;
  accounts: CloudAccount[];
  generation: GenerationParams;
  setGeneration: React.Dispatch<React.SetStateAction<GenerationParams>>;
  generating: boolean;
  onCreate: () => void;
  onFallback: () => void;
  notice: CurUnavailableNotice | null;
}

const CreateDraftDialog: React.FC<CreateDraftDialogProps> = ({
  isOpen, onClose, accounts, generation, setGeneration, generating, onCreate, onFallback, notice,
}) => {
  const periods = useMemo(periodOptions, []);
  const periodValue = `${generation.year}-${String(generation.month).padStart(2, '0')}`;
  const account = accounts.find(a => a.identifier === generation.accountId);
  const isAws = !account || account.provider === 'AWS';

  return (
    <DialogRoot isOpen={isOpen} onClose={generating ? () => undefined : onClose} maxWidth="max-w-xl">
      <DialogPanel
        title="Create draft invoice"
        onClose={generating ? () => undefined : onClose}
        footer={
          <>
            <button type="button" className="inv-btn inv-btn--ghost" onClick={onClose} disabled={generating}>
              Cancel
            </button>
            <button
              type="button"
              className="inv-btn inv-btn--primary"
              onClick={onCreate}
              disabled={!generation.accountId || generating}
            >
              {generating && !notice
                ? <><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Reading charges…</>
                : 'Create draft'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label className="inv-field-label" htmlFor="draft-account">Account</label>
            <select
              id="draft-account"
              className="inv-select"
              value={generation.accountId}
              onChange={e => setGeneration(prev => ({ ...prev, accountId: e.target.value }))}
            >
              <option value="">Select an account…</option>
              {accounts.map(acc => (
                <option key={acc.id} value={acc.identifier}>
                  {acc.name} · {acc.identifier} ({acc.provider})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="inv-field-label" htmlFor="draft-period">Billing period</label>
            <select
              id="draft-period"
              className="inv-select"
              value={periodValue}
              onChange={e => {
                const [y, m] = e.target.value.split('-').map(Number);
                setGeneration(prev => ({ ...prev, year: y, month: m }));
              }}
            >
              {periods.map(p => (
                <option key={p.value} value={p.value}>{p.label}</option>
              ))}
            </select>
          </div>

          <fieldset>
            <legend className="inv-field-label">Invoice type</legend>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <label className="inv-choice" data-selected={!generation.includeIrn}>
                <input
                  type="radio"
                  name="draft-type"
                  checked={!generation.includeIrn}
                  onChange={() => setGeneration(prev => ({ ...prev, includeIrn: false }))}
                />
                <span>
                  <span className="block text-[13px] font-semibold" style={{ color: 'var(--inv-ink)' }}>Proforma invoice</span>
                  <span className="block text-[12px] inv-muted">No IRN on the PDF</span>
                </span>
              </label>
              <label className="inv-choice" data-selected={generation.includeIrn}>
                <input
                  type="radio"
                  name="draft-type"
                  checked={generation.includeIrn}
                  onChange={() => setGeneration(prev => ({ ...prev, includeIrn: true }))}
                />
                <span>
                  <span className="block text-[13px] font-semibold" style={{ color: 'var(--inv-ink)' }}>Tax invoice</span>
                  <span className="block text-[12px] inv-muted">IRN, Ack no., Ack date and QR code</span>
                </span>
              </label>
            </div>
          </fieldset>

          {!notice && (
            <p className="text-[12.5px] inv-muted leading-relaxed">
              {isAws
                ? 'Charges are read from the account’s Cost and Usage Report. If the report can’t cover this month, you’ll see why here before anything is read from Cost Explorer.'
                : 'Charges are read from the account’s billing export.'}
            </p>
          )}

          {notice && (
            <div className="inv-alert inv-alert--warning" role="alert">
              <i className="fas fa-exclamation-triangle" aria-hidden="true" />
              <div className="min-w-0">
                <p className="inv-alert__title">The Cost and Usage Report can&rsquo;t build this draft</p>
                <p className="mt-0.5">{notice.message}</p>
                <p className="mt-1 text-[12px] opacity-80">
                  Nothing has been read from Cost Explorer.
                  {notice.canUseCostExplorer && ' You can build this draft from Cost Explorer instead. Its figures can differ from the report’s.'}
                </p>
                {notice.canUseCostExplorer && (
                  <button
                    type="button"
                    className="inv-btn inv-btn--fallback inv-btn--sm mt-3"
                    onClick={onFallback}
                    disabled={generating}
                  >
                    {generating
                      ? <><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Fetching from Cost Explorer…</>
                      : <><i className="fas fa-cloud-download-alt" aria-hidden="true" /> Fetch from Cost Explorer</>}
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

// ─── Upload draft dialog ─────────────────────────────────────────────────────

interface UploadDraftDialogProps {
  isOpen: boolean;
  onClose: () => void;
  accounts: CloudAccount[];
  fetchClients: () => Promise<ClientOption[]>;
  onPreview: (file: File) => Promise<UploadResult<UploadDraftPreview>>;
  onCreate: (file: File, params: UploadDraftParams) => Promise<UploadResult<number>>;
  onCreated: (id: number) => void;
}

/**
 * Upload an AWS bill PDF, check what was read from it, then create a draft.
 *
 * The server reads the file twice -- once to preview, once to create -- so the draft
 * never depends on anything the browser holds. Account and period default to what the
 * PDF printed and can be changed before creating.
 */
const UploadDraftDialog: React.FC<UploadDraftDialogProps> = ({
  isOpen, onClose, accounts, fetchClients, onPreview, onCreate, onCreated,
}) => {
  const [clients, setClients] = useState<ClientOption[]>([]);
  // Details for an account the bill introduces. clientId null = a new client named clientName.
  const [newAccount, setNewAccount] = useState({ accountName: '', clientId: null as number | null, clientName: '' });
  const periods = useMemo(periodOptions, []);
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<UploadDraftPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'reading' | 'creating' | null>(null);
  const [dragging, setDragging] = useState(false);
  const [params, setParams] = useState<UploadDraftParams>({
    accountId: '', year: new Date().getFullYear(), month: new Date().getMonth() + 1, includeIrn: false,
  });

  useEffect(() => {
    if (!isOpen) {
      setFile(null);
      setPreview(null);
      setError(null);
      setBusy(null);
      setDragging(false);
    }
  }, [isOpen]);

  const choose = async (picked: File | undefined) => {
    if (!picked) return;
    if (!picked.name.toLowerCase().endsWith('.pdf') && picked.type !== 'application/pdf') {
      setError('Choose a PDF file.');
      return;
    }
    setFile(picked);
    setPreview(null);
    setError(null);
    setBusy('reading');
    const result = await onPreview(picked);
    setBusy(null);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    const p = result.value;
    setPreview(p);
    if (p.accountWillBeCreated) {
      const suggested = p.suggestedAccountName ?? `AWS account ${p.accountId}`;
      setNewAccount({ accountName: suggested, clientId: null, clientName: suggested });
      if (clients.length === 0) void fetchClients().then(setClients);
    }
    // A bill for an account BillOps doesn't have yet defaults to adding it.
    const matched = p.account?.identifier
      ?? (p.accountWillBeCreated && p.accountId ? p.accountId : '');
    setParams(prev => ({
      ...prev,
      accountId: matched,
      year: p.year ?? prev.year,
      month: p.month ?? prev.month,
    }));
  };

  const create = async () => {
    if (!file || !params.accountId) return;
    setBusy('creating');
    setError(null);
    const result = await onCreate(file, addingAccount ? {
      ...params,
      newAccount: {
        accountName: newAccount.accountName.trim(),
        clientId: newAccount.clientId,
        clientName: newAccount.clientName.trim(),
      },
    } : params);
    setBusy(null);
    if (result.ok) onCreated(result.value);
    else setError(result.message);
  };

  const periodValue = `${params.year}-${String(params.month).padStart(2, '0')}`;
  // The PDF's own month may be older than the 24 the picker lists.
  const periodList = periods.some(p => p.value === periodValue)
    ? periods
    : [{ value: periodValue, label: formatPeriod(periodValue, true) }, ...periods];
  const accountDiffers = !!preview?.accountId && !!params.accountId && params.accountId !== preview.accountId;
  /** The admin kept the bill's own account, which BillOps doesn't have yet. */
  const addingAccount = !!preview?.accountWillBeCreated && !!preview.accountId && params.accountId === preview.accountId;
  const newAccountIncomplete = addingAccount
    && (!newAccount.accountName.trim() || (newAccount.clientId == null && !newAccount.clientName.trim()));
  const periodDiffers = !!preview?.billingPeriod && preview.billingPeriod !== periodValue;
  const locked = busy !== null;

  return (
    <DialogRoot isOpen={isOpen} onClose={locked ? () => undefined : onClose} maxWidth="max-w-2xl">
      <DialogPanel
        title="Upload draft invoice"
        onClose={locked ? () => undefined : onClose}
        footer={
          <>
            <button type="button" className="inv-btn inv-btn--ghost" onClick={onClose} disabled={locked}>
              Cancel
            </button>
            <button
              type="button"
              className="inv-btn inv-btn--primary"
              onClick={create}
              disabled={!preview || !params.accountId || newAccountIncomplete || locked}
            >
              {busy === 'creating'
                ? <><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Creating draft…</>
                : 'Create draft'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <input
            ref={inputRef}
            type="file"
            accept="application/pdf,.pdf"
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            onChange={e => { void choose(e.target.files?.[0]); e.target.value = ''; }}
          />
          <button
            type="button"
            className="inv-dropzone"
            data-active={dragging}
            disabled={locked}
            onClick={() => inputRef.current?.click()}
            onDragOver={e => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={e => { e.preventDefault(); setDragging(false); void choose(e.dataTransfer.files?.[0]); }}
            aria-describedby="upload-draft-hint"
          >
            {busy === 'reading' ? (
              <>
                <i className="fas fa-spinner fa-spin" aria-hidden="true" />
                <span>Reading {file?.name}…</span>
              </>
            ) : file ? (
              <>
                <i className="fas fa-file-pdf" aria-hidden="true" />
                <span className="font-semibold" style={{ color: 'var(--inv-ink)' }}>{file.name}</span>
                <span className="text-[12px] inv-muted">Choose a different file</span>
              </>
            ) : (
              <>
                <i className="fas fa-cloud-upload-alt" aria-hidden="true" />
                <span className="font-semibold" style={{ color: 'var(--inv-ink)' }}>Choose the AWS bill PDF</span>
                <span className="text-[12px] inv-muted">or drop it here</span>
              </>
            )}
          </button>
          <p id="upload-draft-hint" className="text-[12.5px] inv-muted leading-relaxed">
            Print the bill from the AWS Billing console&rsquo;s Bills page with every service expanded.
            The account, billing period and every charge under &ldquo;Charges by service&rdquo; are read from it.
            The new draft can be edited like any other.
          </p>

          {error && (
            <div className="inv-alert inv-alert--error" role="alert">
              <i className="fas fa-exclamation-circle" aria-hidden="true" />
              <p className="flex-1">{error}</p>
            </div>
          )}

          {preview && (
            <>
              <dl className="inv-upload-facts">
                <div>
                  <dt>Account ID on the bill</dt>
                  <dd className="inv-num">{preview.accountId ?? 'Not found'}</dd>
                </div>
                <div>
                  <dt>Billing period</dt>
                  <dd>{preview.billingPeriod ? formatPeriod(preview.billingPeriod, true) : 'Not found'}</dd>
                </div>
                <div>
                  <dt>Bill total (pre-tax)</dt>
                  <dd className="inv-num">
                    {preview.preTaxTotal != null ? usd(preview.preTaxTotal) : preview.grandTotal != null ? usd(preview.grandTotal) : '—'}
                  </dd>
                </div>
                <div>
                  <dt>Read from the PDF</dt>
                  <dd className="inv-num">
                    {preview.lineItemCount} charges · {usd(preview.lineItemTotal)}
                  </dd>
                </div>
                {preview.roundingLineCount > 0 && (
                  <div>
                    <dt>Rounding lines</dt>
                    <dd className="inv-num" title="AWS rounds each line and each subtotal separately. These lines make every service, region and usage-group total on the draft match the bill exactly.">
                      {preview.roundingLineCount} · {preview.roundingTotal < 0 ? '−' : ''}{usd(Math.abs(preview.roundingTotal))}
                    </dd>
                  </div>
                )}
                {preview.marketplaceTotal != null && preview.marketplaceTotal > 0 && (
                  <div>
                    <dt>Marketplace</dt>
                    <dd className="inv-num">{usd(preview.marketplaceTotal)} (not discounted)</dd>
                  </div>
                )}
              </dl>

              {preview.warnings.length > 0 && (
                <div className="inv-alert inv-alert--warning" role="status">
                  <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                  <ul className="min-w-0 space-y-1">
                    {preview.warnings.map(w => <li key={w}>{w}</li>)}
                  </ul>
                </div>
              )}

              {preview.services.length > 0 && (
                <div>
                  <p className="inv-field-label">Charges by service</p>
                  <ul className="inv-upload-services">
                    {preview.services.map(s => (
                      <li key={s.serviceName}>
                        <span className="min-w-0 truncate" style={{ color: 'var(--inv-ink)' }} title={s.serviceName}>
                          {s.serviceName}
                          <span className="inv-muted"> · {s.lineCount}</span>
                        </span>
                        <span className="inv-num font-semibold">{usd(s.total)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div>
                <label className="inv-field-label" htmlFor="upload-account">Bill to account</label>
                <select
                  id="upload-account"
                  className="inv-select"
                  value={params.accountId}
                  disabled={locked}
                  onChange={e => setParams(prev => ({ ...prev, accountId: e.target.value }))}
                >
                  <option value="">Select an account…</option>
                  {preview.accountWillBeCreated && preview.accountId && (
                    <option value={preview.accountId}>
                      {preview.accountId} · new account from this bill
                    </option>
                  )}
                  {accounts.map(acc => (
                    <option key={acc.id} value={acc.identifier}>
                      {acc.name} · {acc.identifier} ({acc.provider})
                    </option>
                  ))}
                </select>
                {accountDiffers && (
                  <p className="mt-1 text-[12px]" style={{ color: '#b45309' }}>
                    This bill was printed for account {preview.accountId}.
                  </p>
                )}
              </div>

              {addingAccount && (
                <div className="inv-alert inv-alert--info" role="note">
                  <i className="fas fa-info-circle" aria-hidden="true" />
                  <div className="min-w-0 flex-1 space-y-3">
                    <p>
                      Account {preview.accountId} isn&rsquo;t in BillOps yet. It&rsquo;ll be added as a billing-only
                      AWS account when the draft is created. Nothing is read from AWS for it.
                    </p>
                    <div>
                      <label className="inv-field-label" htmlFor="upload-new-account-name">Account name</label>
                      <input
                        id="upload-new-account-name"
                        className="inv-input w-full"
                        value={newAccount.accountName}
                        disabled={locked}
                        maxLength={255}
                        onChange={e => setNewAccount(prev => ({ ...prev, accountName: e.target.value }))}
                      />
                    </div>
                    <div>
                      <label className="inv-field-label" htmlFor="upload-new-account-client">Client</label>
                      <select
                        id="upload-new-account-client"
                        className="inv-select"
                        value={newAccount.clientId ?? ''}
                        disabled={locked}
                        onChange={e => {
                          const v = e.target.value;
                          setNewAccount(prev => ({ ...prev, clientId: v ? Number(v) : null }));
                        }}
                      >
                        <option value="">New client…</option>
                        {clients.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                      </select>
                    </div>
                    {newAccount.clientId == null && (
                      <div>
                        <label className="inv-field-label" htmlFor="upload-new-client-name">New client name</label>
                        <input
                          id="upload-new-client-name"
                          className="inv-input w-full"
                          value={newAccount.clientName}
                          disabled={locked}
                          maxLength={255}
                          onChange={e => setNewAccount(prev => ({ ...prev, clientName: e.target.value }))}
                        />
                        <p className="mt-1 text-[12px] opacity-80">
                          The invoice PDF bills this client. Add its address and GSTIN on the Accounts page.
                          An existing client with the same name is reused.
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              )}

              <div>
                <label className="inv-field-label" htmlFor="upload-period">Billing period</label>
                <select
                  id="upload-period"
                  className="inv-select"
                  value={periodValue}
                  disabled={locked}
                  onChange={e => {
                    const [y, m] = e.target.value.split('-').map(Number);
                    setParams(prev => ({ ...prev, year: y, month: m }));
                  }}
                >
                  {periodList.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
                </select>
                {periodDiffers && (
                  <p className="mt-1 text-[12px]" style={{ color: '#b45309' }}>
                    The bill is for {formatPeriod(preview.billingPeriod, true)}.
                  </p>
                )}
              </div>

              <fieldset>
                <legend className="inv-field-label">Invoice type</legend>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  <label className="inv-choice" data-selected={!params.includeIrn}>
                    <input
                      type="radio"
                      name="upload-type"
                      checked={!params.includeIrn}
                      disabled={locked}
                      onChange={() => setParams(prev => ({ ...prev, includeIrn: false }))}
                    />
                    <span>
                      <span className="block text-[13px] font-semibold" style={{ color: 'var(--inv-ink)' }}>Proforma invoice</span>
                      <span className="block text-[12px] inv-muted">No IRN on the PDF</span>
                    </span>
                  </label>
                  <label className="inv-choice" data-selected={params.includeIrn}>
                    <input
                      type="radio"
                      name="upload-type"
                      checked={params.includeIrn}
                      disabled={locked}
                      onChange={() => setParams(prev => ({ ...prev, includeIrn: true }))}
                    />
                    <span>
                      <span className="block text-[13px] font-semibold" style={{ color: 'var(--inv-ink)' }}>Tax invoice</span>
                      <span className="block text-[12px] inv-muted">IRN, Ack no., Ack date and QR code</span>
                    </span>
                  </label>
                </div>
              </fieldset>
            </>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

// ─── Page ────────────────────────────────────────────────────────────────────

const BillOpsAdminInvoicesPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    loading,
    isUpdating,
    error,
    updateError,
    mergeSuccess,
    lastUpdatedDisplay,
    selectedIds,
    generation,
    generating,
    curUnavailable,
    filteredInvoices,
    canMergeSelection,
    mergeButtonText,
    filterIds,
    accounts,
    fetchData,
    generateInvoice,
    generateFromCostExplorer,
    dismissCurUnavailable,
    previewUploadedDraft,
    createDraftFromUpload,
    fetchClients,
    setSelectedIds,
    setFilterIds,
    setGeneration,
    mergeSelectedInvoices,
    downloadPreview,
    isCloudFront,
    requestMerge,
    cancelMerge,
    pendingMerge,
    requestDelete,
    cancelDelete,
    confirmDelete,
    pendingDelete,
    dismissMergeSuccess,
    clearUpdateError,
  } = useBillOpsAdminInvoices();

  const { setActions, clearActions } = useTopbar();

  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [period, setPeriod] = useState('');
  const [sort, setSort] = useState<SortState>({ key: 'newest', dir: 'desc' });
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);

  // ── Topbar: freshness and refresh, as on the other BillOps pages ──────────
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {lastUpdatedDisplay && (
          <span className="text-xs text-slate-500 font-medium hidden md:block">Updated {lastUpdatedDisplay}</span>
        )}
        <button
          type="button"
          onClick={() => fetchData(true)}
          disabled={loading || isUpdating}
          className="w-9 h-9 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-lg transition-all disabled:opacity-50"
          title="Refresh invoices"
          aria-label="Refresh invoices"
        >
          <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`} aria-hidden="true" />
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, lastUpdatedDisplay, loading, isUpdating, fetchData]);

  // ── Derived lists ─────────────────────────────────────────────────────────

  /** Account-scoped invoices — what the tiles summarise. */
  const scoped = filteredInvoices;

  const counts = useMemo(() => ({
    all: scoped.length,
    draft: scoped.filter(i => i.status === 'DRAFT').length,
    finalized: scoped.filter(i => i.status === 'FINALIZED').length,
    void: scoped.filter(i => i.status === 'VOID').length,
    finalizedValue: scoped.filter(i => i.status === 'FINALIZED').reduce((s, i) => s + (i.amount || 0), 0),
    draftValue: scoped.filter(i => i.status === 'DRAFT').reduce((s, i) => s + (i.amount || 0), 0),
  }), [scoped]);

  const periodsInData = useMemo(
    () => Array.from(new Set(scoped.map(i => i.billingPeriod).filter(Boolean))).sort().reverse(),
    [scoped],
  );

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    const rows = scoped.filter(inv => {
      if (status !== 'ALL' && inv.status !== status) return false;
      if (period && inv.billingPeriod !== period) return false;
      if (!q) return true;
      return [inv.invoiceNumber, inv.accountName, accountIdOf(inv), inv.billingPeriod, formatPeriod(inv.billingPeriod, true)]
        .some(v => (v || '').toLowerCase().includes(q));
    });
    const dir = sort.dir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      switch (sort.key) {
        case 'account': return dir * (a.accountName || '').localeCompare(b.accountName || '');
        case 'period': return dir * (a.billingPeriod || '').localeCompare(b.billingPeriod || '') || b.id - a.id;
        case 'total': return dir * ((a.amount || 0) - (b.amount || 0));
        default: return dir * (a.id - b.id);
      }
    });
  }, [scoped, search, status, period, sort]);

  // Back to the first page whenever the set being paged changes.
  useEffect(() => { setPage(0); }, [search, status, period, sort, filterIds]);

  const pageCount = Math.max(1, Math.ceil(visible.length / PAGE_SIZE));
  const pageRows = visible.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
  const filtersActive = Boolean(search || status !== 'ALL' || period || filterIds.length);

  const clearFilters = () => {
    setSearch('');
    setStatus('ALL');
    setPeriod('');
    setFilterIds([]);
  };

  const toggleSort = (key: SortKey) =>
    setSort(prev => (prev.key === key
      ? { key, dir: prev.dir === 'asc' ? 'desc' : 'asc' }
      : { key, dir: key === 'account' ? 'asc' : 'desc' }));

  const sortIcon = (key: SortKey) =>
    sort.key !== key ? 'fa-sort' : sort.dir === 'asc' ? 'fa-sort-up' : 'fa-sort-down';

  const ariaSort = (key: SortKey): React.AriaAttributes['aria-sort'] =>
    sort.key !== key ? 'none' : sort.dir === 'asc' ? 'ascending' : 'descending';

  const toggleSelected = (id: string, checked: boolean) =>
    setSelectedIds(prev => (checked ? [...prev, id] : prev.filter(x => x !== id)));

  const openDetail = useCallback((id: number) => navigate(`/billops-admin-invoice-detail?id=${id}`), [navigate]);

  // ── Create draft ──────────────────────────────────────────────────────────

  const openCreate = () => {
    // Pre-fill the account being viewed, when exactly one is.
    if (filterIds.length === 1) {
      const acc = accounts.find(a => String(a.id) === filterIds[0]);
      if (acc) setGeneration(prev => ({ ...prev, accountId: acc.identifier }));
    }
    setCreateOpen(true);
  };

  const closeCreate = () => {
    setCreateOpen(false);
    dismissCurUnavailable();
  };

  const afterCreate = (id: number | null) => {
    if (id == null) return; // Refused (notice shown in the dialog) or failed (alerted).
    setCreateOpen(false);
    openDetail(id);
  };

  // ── Render ────────────────────────────────────────────────────────────────

  const pendingDeleteInvoice = scoped.find(i => i.id === pendingDelete);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <AlertDialog
        isOpen={!!pendingMerge}
        title="Merge these invoices?"
        description={`${mergeButtonText}. The line items are combined into one invoice and the other draft is deleted.`}
        confirmLabel="Merge"
        variant="warning"
        onConfirm={mergeSelectedInvoices}
        onCancel={cancelMerge}
      />
      <AlertDialog
        isOpen={pendingDelete !== null}
        title="Delete this draft?"
        description={`${pendingDeleteInvoice?.invoiceNumber ?? 'This draft'} will be permanently removed. This can't be undone.`}
        confirmLabel="Delete draft"
        variant="danger"
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
      <CreateDraftDialog
        isOpen={createOpen}
        onClose={closeCreate}
        accounts={accounts}
        generation={generation}
        setGeneration={setGeneration}
        generating={generating}
        notice={curUnavailable}
        onCreate={async () => afterCreate(await generateInvoice())}
        onFallback={async () => afterCreate(await generateFromCostExplorer())}
      />
      <UploadDraftDialog
        isOpen={uploadOpen}
        onClose={() => setUploadOpen(false)}
        accounts={accounts}
        fetchClients={fetchClients}
        onPreview={previewUploadedDraft}
        onCreate={createDraftFromUpload}
        onCreated={id => { setUploadOpen(false); openDetail(id); }}
      />

      <main className="inv-page">
        {/* Page header */}
        <div className="flex items-end justify-between gap-4 flex-wrap">
          <div>
            <h1 className="inv-h1">Invoices</h1>
            <p className="inv-desc">
              Draft, review and finalize customer invoices. AWS drafts are built from each account&rsquo;s Cost and Usage Report.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" className="inv-btn" onClick={() => setUploadOpen(true)}>
              <i className="fas fa-file-upload" aria-hidden="true" />
              Upload draft
            </button>
            <button type="button" className="inv-btn inv-btn--primary" onClick={openCreate}>
              <i className="fas fa-plus" aria-hidden="true" />
              Create draft
            </button>
          </div>
        </div>

        {/* Notices */}
        {mergeSuccess && (
          <div className="inv-alert inv-alert--success" role="status">
            <i className="fas fa-check-circle" aria-hidden="true" />
            <p className="flex-1">Invoices merged.</p>
            <button type="button" className="inv-icon-btn" onClick={dismissMergeSuccess} aria-label="Dismiss">
              <i className="fas fa-times" aria-hidden="true" />
            </button>
          </div>
        )}
        {updateError && !loading && (
          <div className="inv-alert inv-alert--warning" role="alert">
            <i className="fas fa-exclamation-triangle" aria-hidden="true" />
            <p className="flex-1">Couldn&rsquo;t refresh: {updateError}</p>
            <button type="button" className="inv-icon-btn" onClick={clearUpdateError} aria-label="Dismiss">
              <i className="fas fa-times" aria-hidden="true" />
            </button>
          </div>
        )}

        {/* Summary strip — the tiles are also the quickest status filter */}
        <section className="inv-card overflow-hidden" aria-label="Summary">
          <div className="inv-kpis">
            <button
              type="button"
              className="inv-kpi"
              onClick={() => setStatus('ALL')}
              title="Show every status"
            >
              <p className="inv-kpi__label">Invoices</p>
              <p className="inv-kpi__value">{loading ? '—' : counts.all.toLocaleString()}</p>
              <p className="inv-kpi__sub">
                {filterIds.length ? 'For the selected account' : 'Across all accounts'}
              </p>
            </button>
            <button
              type="button"
              className="inv-kpi"
              aria-pressed={status === 'DRAFT'}
              onClick={() => setStatus(status === 'DRAFT' ? 'ALL' : 'DRAFT')}
            >
              <p className="inv-kpi__label">Drafts to review</p>
              <p className="inv-kpi__value">{loading ? '—' : counts.draft.toLocaleString()}</p>
              <p className="inv-kpi__sub" title={usd(counts.draftValue)}>{usdCompact(counts.draftValue)} in drafts</p>
            </button>
            <button
              type="button"
              className="inv-kpi"
              aria-pressed={status === 'FINALIZED'}
              onClick={() => setStatus(status === 'FINALIZED' ? 'ALL' : 'FINALIZED')}
            >
              <p className="inv-kpi__label">Finalized</p>
              <p className="inv-kpi__value">{loading ? '—' : counts.finalized.toLocaleString()}</p>
              <p className="inv-kpi__sub">Sent to clients</p>
            </button>
            <div className="inv-kpi">
              <p className="inv-kpi__label">Finalized value</p>
              <p className="inv-kpi__value" title={usd(counts.finalizedValue)}>
                {loading ? '—' : usdCompact(counts.finalizedValue)}
              </p>
              <p className="inv-kpi__sub">Including tax</p>
            </div>
          </div>
        </section>

        {/* Invoices table */}
        <section className="inv-card" aria-labelledby="invoices-heading">
          <div className="inv-card__header">
            <div>
              <h2 id="invoices-heading" className="inv-h2">
                Invoices <span className="inv-count">({visible.length.toLocaleString()})</span>
              </h2>
              <p className="inv-desc">Select a draft to review its charges, apply discounts and finalize it.</p>
            </div>
          </div>

          {/* Filters */}
          <div className="flex flex-wrap items-center gap-2.5 px-5 py-3" style={{ borderBottom: '1px solid var(--inv-divider)' }}>
            <div className="inv-search flex-1 max-w-sm">
              <i className="fas fa-search" aria-hidden="true" />
              <input
                type="search"
                className="inv-input"
                placeholder="Find by invoice, account or period"
                value={search}
                onChange={e => setSearch(e.target.value)}
                aria-label="Search invoices"
              />
            </div>
            <select
              className="inv-select"
              style={{ width: 'auto', minWidth: 190 }}
              value={filterIds[0] || ''}
              onChange={e => setFilterIds(e.target.value ? [e.target.value] : [])}
              aria-label="Filter by account"
            >
              <option value="">All accounts</option>
              {accounts.map(acc => (
                <option key={acc.id} value={String(acc.id)}>{acc.name} ({acc.identifier})</option>
              ))}
            </select>
            <select
              className="inv-select"
              style={{ width: 'auto', minWidth: 150 }}
              value={period}
              onChange={e => setPeriod(e.target.value)}
              aria-label="Filter by billing period"
            >
              <option value="">All periods</option>
              {periodsInData.map(p => (
                <option key={p} value={p}>{formatPeriod(p, true)}</option>
              ))}
            </select>
            <div className="inv-segmented" role="group" aria-label="Filter by status">
              {(['ALL', 'DRAFT', 'FINALIZED', 'VOID'] as StatusFilter[]).map(s => (
                <button key={s} type="button" aria-pressed={status === s} onClick={() => setStatus(s)}>
                  {s === 'ALL' ? 'All' : s === 'DRAFT' ? 'Draft' : s === 'FINALIZED' ? 'Finalized' : 'Void'}
                  {s !== 'ALL' && (
                    <span className="inv-seg-count">
                      {s === 'DRAFT' ? counts.draft : s === 'FINALIZED' ? counts.finalized : counts.void}
                    </span>
                  )}
                </button>
              ))}
            </div>
            {filtersActive && (
              <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={clearFilters}>
                Clear filters
              </button>
            )}
          </div>

          {/* Selection & merge */}
          {selectedIds.length > 0 && (
            <div className="inv-selection" role="status">
              <span>
                <strong style={{ color: 'var(--inv-ink)' }}>{selectedIds.length} selected</strong>
                <span className="ml-2">
                  {selectedIds.length !== 2
                    ? 'Select exactly two drafts for the same account and period to merge them.'
                    : !canMergeSelection
                      ? 'These two can’t be merged — they must be drafts for the same account and period.'
                      : mergeButtonText}
                </span>
              </span>
              <span className="flex items-center gap-2">
                <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={() => setSelectedIds([])}>
                  Clear
                </button>
                <button
                  type="button"
                  className="inv-btn inv-btn--primary inv-btn--sm"
                  onClick={requestMerge}
                  disabled={!canMergeSelection}
                >
                  <i className="fas fa-code-branch" aria-hidden="true" />
                  Merge
                </button>
              </span>
            </div>
          )}

          {/* Critical error with nothing cached */}
          {error && !loading && scoped.length === 0 ? (
            <div className="p-5">
              <div className="inv-alert inv-alert--error" role="alert">
                <i className="fas fa-times-circle" aria-hidden="true" />
                <div className="flex-1">
                  <p className="inv-alert__title">Couldn&rsquo;t load invoices</p>
                  <p>{error}</p>
                </div>
                <button type="button" className="inv-btn inv-btn--sm" onClick={() => fetchData(true)}>Try again</button>
              </div>
            </div>
          ) : (
            <div className="inv-table-wrap">
              <table className="inv-table">
                <thead>
                  <tr>
                    <th style={{ width: 44 }}><span className="sr-only">Select</span></th>
                    <th aria-sort={ariaSort('newest')}>
                      <button type="button" className="inv-sort" onClick={() => toggleSort('newest')}>
                        Invoice <i className={`fas ${sortIcon('newest')}`} aria-hidden="true" />
                      </button>
                    </th>
                    <th aria-sort={ariaSort('account')}>
                      <button type="button" className="inv-sort" onClick={() => toggleSort('account')}>
                        Account <i className={`fas ${sortIcon('account')}`} aria-hidden="true" />
                      </button>
                    </th>
                    <th aria-sort={ariaSort('period')}>
                      <button type="button" className="inv-sort" onClick={() => toggleSort('period')}>
                        Billing period <i className={`fas ${sortIcon('period')}`} aria-hidden="true" />
                      </button>
                    </th>
                    <th>Source</th>
                    <th>Status</th>
                    <th className="is-num" aria-sort={ariaSort('total')}>
                      <button type="button" className="inv-sort" onClick={() => toggleSort('total')}>
                        Total (USD) <i className={`fas ${sortIcon('total')}`} aria-hidden="true" />
                      </button>
                    </th>
                    <th className="is-num"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {loading && Array.from({ length: 6 }).map((_, i) => (
                    <tr key={`sk-${i}`}>
                      <td><div className="inv-shimmer" style={{ width: 15, height: 15 }} /></td>
                      <td><div className="inv-shimmer" style={{ width: 150, height: 12 }} /></td>
                      <td><div className="inv-shimmer" style={{ width: 170, height: 12 }} /></td>
                      <td><div className="inv-shimmer" style={{ width: 80, height: 12 }} /></td>
                      <td><div className="inv-shimmer" style={{ width: 50, height: 12 }} /></td>
                      <td><div className="inv-shimmer" style={{ width: 70, height: 18, borderRadius: 999 }} /></td>
                      <td className="is-num"><div className="inv-shimmer ml-auto" style={{ width: 80, height: 12 }} /></td>
                      <td />
                    </tr>
                  ))}

                  {!loading && pageRows.map(invoice => {
                    const id = String(invoice.id);
                    const selected = selectedIds.includes(id);
                    const isDraft = invoice.status === 'DRAFT';
                    const cf = isCloudFront(invoice);
                    return (
                      <tr
                        key={invoice.id}
                        className={`is-clickable ${selected ? 'is-selected' : ''}`}
                        onClick={() => openDetail(invoice.id)}
                      >
                        <td onClick={e => e.stopPropagation()}>
                          <input
                            type="checkbox"
                            className="inv-checkbox"
                            checked={selected}
                            disabled={!isDraft}
                            onChange={e => toggleSelected(id, e.target.checked)}
                            aria-label={`Select ${invoice.invoiceNumber}`}
                            title={isDraft ? 'Select to merge' : 'Only drafts can be merged'}
                          />
                        </td>
                        <td>
                          <span className={`inv-type-tag ${cf ? 'inv-type-tag--cf' : ''}`} title={cf ? 'CloudFront invoice' : 'Standard invoice'}>
                            {cf ? 'CF' : 'STD'}
                          </span>
                          <button
                            type="button"
                            className="inv-link"
                            onClick={e => { e.stopPropagation(); openDetail(invoice.id); }}
                          >
                            {invoice.invoiceNumber}
                          </button>
                        </td>
                        <td>
                          <div className="font-semibold" style={{ color: 'var(--inv-ink)' }}>{invoice.accountName}</div>
                          <div className="text-[12px] inv-muted inv-num">{accountIdOf(invoice)}</div>
                        </td>
                        <td className="whitespace-nowrap" title={formatPeriod(invoice.billingPeriod, true)}>
                          {formatPeriod(invoice.billingPeriod)}
                        </td>
                        <td><SourcePills invoice={invoice} /></td>
                        <td><StatusPill status={invoice.status} /></td>
                        <td className="is-num inv-num font-semibold whitespace-nowrap">{usd(invoice.amount)}</td>
                        <td className="is-num whitespace-nowrap" onClick={e => e.stopPropagation()}>
                          <button
                            type="button"
                            className="inv-icon-btn"
                            onClick={() => downloadPreview(invoice)}
                            title="Download PDF"
                            aria-label={`Download PDF for ${invoice.invoiceNumber}`}
                          >
                            <i className="fas fa-file-pdf" aria-hidden="true" />
                          </button>
                          <button
                            type="button"
                            className="inv-icon-btn inv-icon-btn--danger"
                            onClick={() => requestDelete(invoice.id)}
                            disabled={!isDraft}
                            title={isDraft ? 'Delete draft' : 'Only drafts can be deleted'}
                            aria-label={`Delete ${invoice.invoiceNumber}`}
                          >
                            <i className="fas fa-trash-alt" aria-hidden="true" />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              {!loading && visible.length === 0 && (
                <div className="inv-empty">
                  {scoped.length === 0 ? (
                    <>
                      <i className="fas fa-file-invoice-dollar" aria-hidden="true" />
                      <strong>No invoices yet</strong>
                      Create a draft to start billing this account.
                      <div className="mt-4">
                        <button type="button" className="inv-btn inv-btn--primary" onClick={openCreate}>
                          <i className="fas fa-plus" aria-hidden="true" /> Create draft
                        </button>
                      </div>
                    </>
                  ) : (
                    <>
                      <i className="fas fa-filter" aria-hidden="true" />
                      <strong>No invoices match these filters</strong>
                      <div className="mt-3">
                        <button type="button" className="inv-btn inv-btn--sm" onClick={clearFilters}>Clear filters</button>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
          )}

          {!loading && visible.length > PAGE_SIZE && (
            <div className="inv-pager">
              <span className="inv-num">
                {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, visible.length)} of {visible.length}
              </span>
              <span className="flex items-center gap-1">
                <button
                  type="button"
                  className="inv-icon-btn"
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  aria-label="Previous page"
                >
                  <i className="fas fa-chevron-left" aria-hidden="true" />
                </button>
                <span className="inv-num px-1">Page {page + 1} of {pageCount}</span>
                <button
                  type="button"
                  className="inv-icon-btn"
                  onClick={() => setPage(p => Math.min(pageCount - 1, p + 1))}
                  disabled={page >= pageCount - 1}
                  aria-label="Next page"
                >
                  <i className="fas fa-chevron-right" aria-hidden="true" />
                </button>
              </span>
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

export default BillOpsAdminInvoicesPage;
