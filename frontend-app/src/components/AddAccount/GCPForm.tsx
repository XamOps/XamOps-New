import React, { useState, useCallback, useEffect, useRef } from 'react';
import { Icon } from '@iconify/react';
import { apiFetch } from '../../utils/apiClient';

const SCRIPT_URL = 'https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh';

const inputCls = 'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

const GCPForm: React.FC = () => {
  const [accountName, setAccountName] = useState('');
  const [projectId, setProjectId] = useState('');
  const [accessType, setAccessType] = useState<'read-only' | 'read-write'>('read-only');
  const [serviceAccountKey, setServiceAccountKey] = useState('');
  const [billingTable, setBillingTable] = useState('');
  const [isCspManaged, setIsCspManaged] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [commandText, setCommandText] = useState(`sh <(curl -s ${SCRIPT_URL}) YOUR_PROJECT_ID`);
  const [copiedCmd, setCopiedCmd] = useState(false);
  const [showGuide, setShowGuide] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  const step4Ref = useRef<HTMLFormElement>(null);

  useEffect(() => {
    const pid = projectId.trim() || 'YOUR_PROJECT_ID';
    const suffix = accessType === 'read-write' ? ' read-write' : '';
    setCommandText(`sh <(curl -s ${SCRIPT_URL}) ${pid}${suffix}`);
  }, [projectId, accessType]);

  const handleCopyCmd = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(commandText);
      setCopiedCmd(true);
      setTimeout(() => setCopiedCmd(false), 2000);
    } catch { /* ignore */ }
  }, [commandText]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!accountName.trim() || !projectId.trim() || !serviceAccountKey.trim()) {
      setError('Please fill in all required fields: Account Name, GCP Project ID, and Service Account Key.');
      return;
    }

    try {
      const parsed = JSON.parse(serviceAccountKey);
      if (!parsed.type || !parsed.project_id || !parsed.private_key || !parsed.client_email) {
        throw new Error('Missing required fields in JSON');
      }
    } catch (e) {
      setError(`Invalid Service Account JSON: ${e instanceof Error ? e.message : 'Unknown error'}`);
      return;
    }

    if (!isCspManaged && billingTable.trim()) {
      const parts = billingTable.trim().split('.');
      if (parts.length !== 3 || parts.some(p => !p.trim())) {
        setError('BigQuery table must be in format: project.dataset.table');
        return;
      }
    }

    setIsLoading(true);
    try {
      const res = await apiFetch('/api/xamops/account-manager/add-gcp-account', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountName: accountName.trim(),
          projectId: projectId.trim(),
          accessType,
          serviceAccountKey: serviceAccountKey.trim(),
          cspManaged: isCspManaged,
          // Reseller (CSP billing config) is resolved server-side from the tenant.
          ...(!isCspManaged && billingTable.trim() && { billingExportTable: billingTable.trim() }),
        }),
      });

      if (res.ok) {
        setSuccess(true);
      } else {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.error || errData.message || 'Failed to add account');
      }
    } catch (err) {
      setError(`Failed to add GCP account: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [accountName, projectId, accessType, serviceAccountKey, billingTable, isCspManaged]);

  // ── Success screen ──
  if (success) {
    return (
      <div className="max-w-lg mx-auto py-16 text-center">
        <div className="w-14 h-14 rounded-full bg-emerald-50 border border-emerald-200 flex items-center justify-center mx-auto mb-4">
          <Icon icon="mdi:check" width={28} className="text-emerald-600" />
        </div>
        <h2 className="text-[18px] font-bold text-black mb-2">GCP Account Connected</h2>
        <p className="text-[13px] text-[rgba(0,0,0,0.5)] mb-6">
          {accountName} has been verified and connected to XamOps.
        </p>
        <a href="/account-manager" className="inline-flex items-center gap-2 px-5 py-2.5 bg-[#4285F4] text-white text-[13px] font-medium rounded-lg hover:bg-[#3367D6] transition-colors">
          Go to Accounts
          <Icon icon="mdi:arrow-right" width={16} />
        </a>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-[#4285F4]/10 border border-[#4285F4]/20 flex items-center justify-center">
          <Icon icon="mdi:google-cloud" width={22} className="text-[#4285F4]" />
        </div>
        <div>
          <h2 className="text-[16px] font-bold text-black">Connect GCP Account</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Set up a Service Account and connect your GCP project.</p>
        </div>
      </div>

      {/* Cloud Shell Command Block */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <p className={labelCls}>Cloud Shell Command</p>
          <a
            href="https://shell.cloud.google.com/"
            target="_blank"
            rel="noopener noreferrer"
            className="text-[11px] font-medium text-[#4285F4] hover:underline flex items-center gap-1"
          >
            Open Cloud Shell <Icon icon="mdi:open-in-new" width={12} />
          </a>
        </div>
        <div className="rounded-lg overflow-hidden border border-[rgba(0,0,0,0.08)]">
          <div className="flex items-center justify-between px-4 py-2 bg-[#1e1e1e]">
            <span className="text-[11px] text-[rgba(255,255,255,0.5)] font-medium">bash</span>
            <button
              type="button"
              onClick={handleCopyCmd}
              className={`flex items-center gap-1.5 text-[11px] font-medium px-2 py-1 rounded transition-colors ${
                copiedCmd
                  ? 'text-emerald-400'
                  : 'text-[rgba(255,255,255,0.6)] hover:text-white'
              }`}
            >
              {copiedCmd ? <><Icon icon="mdi:check" width={13} />Copied</> : <><Icon icon="mdi:content-copy" width={13} />Copy</>}
            </button>
          </div>
          <div className="px-4 py-3 bg-[#111] font-mono text-[12px] text-[rgba(255,255,255,0.8)] leading-relaxed overflow-x-auto">
            <span className="text-green-400">$</span>{' '}
            <span>sh &lt;(curl -s </span>
            <span className="text-amber-300">{SCRIPT_URL}</span>
            <span>){' '}</span>
            <span className={projectId.trim() ? 'text-sky-300' : 'text-[rgba(255,255,255,0.35)]'}>
              {projectId.trim() || 'YOUR_PROJECT_ID'}
            </span>
          </div>
        </div>
        <p className={hintCls}>Fill in GCP Project ID below to auto-update this command.</p>
      </div>

      {/* Expandable Guide Toggle */}
      <button
        type="button"
        onClick={() => setShowGuide(p => !p)}
        className="w-full flex items-center justify-between px-4 py-3 bg-[#4285F4]/[0.04] hover:bg-[#4285F4]/[0.08] border border-[#4285F4]/20 rounded-lg text-[13px] font-medium text-[#4285F4] transition-colors"
      >
        <div className="flex items-center gap-2">
          <Icon icon="mdi:console" width={16} />
          <span>{showGuide ? 'Hide Setup Guide' : 'View Detailed Setup Guide (Steps 1–3)'}</span>
        </div>
        <Icon icon="mdi:chevron-down" width={18} className={`transition-transform duration-200 ${showGuide ? 'rotate-180' : ''}`} />
      </button>

      {/* Expandable Guide Content */}
      {showGuide && (
        <div className="border border-[rgba(0,0,0,0.08)] rounded-lg p-5 space-y-5">
          {/* Step 1 */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <span className="w-5 h-5 rounded-full bg-[#4285F4] text-white text-[10px] font-bold flex items-center justify-center">1</span>
              <h3 className="text-[13px] font-semibold text-black">Create Service Account & Enable APIs</h3>
            </div>
            <p className="text-[12px] text-[rgba(0,0,0,0.55)] mb-2 leading-relaxed">
              Open <a href="https://shell.cloud.google.com/" target="_blank" rel="noopener noreferrer" className="text-[#4285F4] font-medium underline underline-offset-2">Google Cloud Shell</a> and run the command above. It enables 62 APIs and creates a service account automatically.
            </p>
            <div className="flex items-start gap-2 p-3 bg-amber-50 border border-amber-100 rounded-lg">
              <Icon icon="mdi:shield-alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
              <p className="text-[11px] text-amber-800 leading-relaxed">
                <strong>Required:</strong> Run as project Owner or user with Project IAM Admin + Service Account Admin + Service Account Key Admin roles.
              </p>
            </div>
          </div>

          <div className="border-t border-[rgba(0,0,0,0.06)]" />

          {/* Step 2 */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <span className="w-5 h-5 rounded-full bg-[#4285F4] text-white text-[10px] font-bold flex items-center justify-center">2</span>
              <h3 className="text-[13px] font-semibold text-black">Enable BigQuery Billing Export</h3>
            </div>
            <ol className="space-y-1 text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
              <li>→ Go to <a href="https://console.cloud.google.com/billing" target="_blank" rel="noopener noreferrer" className="text-[#4285F4] font-medium underline underline-offset-2">GCP Console → Billing</a></li>
              <li>→ Navigate to Billing export → BigQuery export</li>
              <li>→ Edit "Standard usage cost", select project, create/select dataset <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-[11px]">billing_export</code>, Save</li>
              <li>→ Note the full table name for below</li>
            </ol>
          </div>

          <div className="border-t border-[rgba(0,0,0,0.06)]" />

          {/* Step 3 */}
          <div>
            <div className="flex items-center gap-2 mb-2">
              <span className="w-5 h-5 rounded-full bg-[#4285F4] text-white text-[10px] font-bold flex items-center justify-center">3</span>
              <h3 className="text-[13px] font-semibold text-black">Grant BigQuery Access</h3>
            </div>
            <p className="text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
              In BigQuery Console, share the <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-[11px]">billing_export</code> dataset with the service account (<code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-[11px]">xamops@…</code>), granting it the BigQuery Data Viewer role.
            </p>
          </div>
        </div>
      )}

      {/* Form */}
      <form onSubmit={handleSubmit} className="space-y-5" ref={step4Ref}>
        {/* Account Name */}
        <div>
          <label htmlFor="gcpAccountName" className={labelCls}>Account Name</label>
          <input
            id="gcpAccountName"
            type="text"
            placeholder="e.g. Production GCP"
            value={accountName}
            onChange={(e) => setAccountName(e.target.value)}
            className={inputCls}
            required
          />
          <p className={hintCls}>A friendly name to identify this account</p>
        </div>

        {/* Project ID */}
        <div>
          <label htmlFor="gcpProjectId" className={labelCls}>GCP Project ID</label>
          <input
            id="gcpProjectId"
            type="text"
            placeholder="your-gcp-project-id"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            className={inputCls}
            required
          />
          <p className={hintCls}>Updates the Cloud Shell command automatically</p>
        </div>

        {/* Access Level */}
        <div>
          <p className={labelCls}>Access Level</p>
          <div className="space-y-2 mt-2">
            <label
              className={`flex items-start gap-3 p-4 rounded-lg border cursor-pointer transition-all ${
                accessType === 'read-only' ? 'border-[#4285F4] bg-[#4285F4]/[0.03] shadow-sm shadow-[#4285F4]/10' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
              }`}
            >
              <input
                type="radio"
                name="gcp-access-type"
                checked={accessType === 'read-only'}
                onChange={() => setAccessType('read-only')}
                className="accent-[#4285F4] mt-0.5"
              />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-[13px] font-medium text-black">Read-Only Access</span>
                  <Icon icon="mdi:shield-check-outline" width={14} className="text-emerald-500" />
                </div>
                <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5">Costs, usage, and inventory visibility. No modifications.</p>
              </div>
            </label>

            <label
              className={`flex items-start gap-3 p-4 rounded-lg border cursor-pointer transition-all ${
                accessType === 'read-write' ? 'border-[#4285F4] bg-[#4285F4]/[0.03] shadow-sm shadow-[#4285F4]/10' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
              }`}
            >
              <input
                type="radio"
                name="gcp-access-type"
                checked={accessType === 'read-write'}
                onChange={() => setAccessType('read-write')}
                className="accent-[#4285F4] mt-0.5"
              />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-[13px] font-medium text-black">Read & Write Access</span>
                  <Icon icon="mdi:shield-edit-outline" width={14} className="text-amber-500" />
                </div>
                <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5">Full visibility plus resource management and operational actions.</p>
              </div>
            </label>
          </div>

          {accessType === 'read-write' && (
            <div className="flex items-start gap-2 p-3 mt-2 bg-amber-50 border border-amber-100 rounded-lg">
              <Icon icon="mdi:shield-alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
              <p className="text-[11px] text-amber-800 leading-relaxed">
                The Cloud Shell command will include <code className="bg-amber-100 px-1 rounded">read-write</code> automatically to grant additional compute and GKE write permissions.
              </p>
            </div>
          )}
        </div>

        {/* Service Account Key */}
        <div>
          <label htmlFor="gcpServiceAccountKey" className={labelCls}>Service Account Key (JSON)</label>
          <textarea
            id="gcpServiceAccountKey"
            placeholder={`{\n  "type": "service_account",\n  "project_id": "your-project-id",\n  "private_key": "...",\n  "client_email": "xamops@..."\n}`}
            value={serviceAccountKey}
            onChange={(e) => setServiceAccountKey(e.target.value)}
            rows={7}
            required
            className={`${inputCls} resize-none font-mono`}
          />
          <p className={hintCls}>Paste the complete JSON output from the Cloud Shell script</p>
        </div>

        {/* CSP Managed toggle */}
        <div>
          <p className={labelCls}>Is your GCP billing managed by Xammer?</p>
          <div className="flex flex-col gap-2 mt-2">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="cspManaged"
                checked={!isCspManaged}
                onChange={() => setIsCspManaged(false)}
                className="accent-[#4285F4]"
              />
              <span className="text-[13px] text-[rgba(0,0,0,0.7)]">No — I manage my own billing export</span>
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="cspManaged"
                checked={isCspManaged}
                onChange={() => { setIsCspManaged(true); setBillingTable(''); }}
                className="accent-[#4285F4]"
              />
              <span className="text-[13px] text-[rgba(0,0,0,0.7)]">Yes — my billing is managed by Xammer</span>
            </label>
          </div>

          {isCspManaged && (
            <div className="flex items-start gap-2 p-3 mt-3 bg-[#4285F4]/[0.04] border border-[#4285F4]/20 rounded-lg">
              <Icon icon="mdi:information-outline" width={16} className="text-[#4285F4] flex-shrink-0 mt-0.5" />
              <p className="text-[11px] text-[rgba(0,0,0,0.6)] leading-relaxed">
                Your billing is handled by Xammer. Cost data is pulled automatically from the managed billing dataset — no further setup needed.
              </p>
            </div>
          )}
        </div>

        {/* BigQuery Billing Table — only when NOT CSP-managed */}
        {!isCspManaged && (
          <div>
            <label htmlFor="gcpBillingTable" className={labelCls}>
              BigQuery Billing Export Table <span className="text-[11px] font-normal text-[rgba(0,0,0,0.35)]">(Optional)</span>
            </label>
            <input
              id="gcpBillingTable"
              type="text"
              placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX"
              value={billingTable}
              onChange={(e) => setBillingTable(e.target.value)}
              className={`${inputCls} font-mono text-[12px]`}
            />
            <p className={hintCls}>Format: project.dataset.table — May take up to 24h to appear. Add later from Account Manager.</p>
          </div>
        )}

        {/* Error */}
        {error && (
          <div className="flex items-center gap-2 px-3 py-2.5 bg-red-50 border border-red-100 rounded-lg">
            <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 flex-shrink-0" />
            <span className="text-[12px] text-red-700">{error}</span>
          </div>
        )}

        {/* Submit */}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full py-3 bg-[#4285F4] text-white text-[13px] font-semibold rounded-lg hover:bg-[#3367D6] transition-colors disabled:opacity-40 flex items-center justify-center gap-2 shadow-sm shadow-[#4285F4]/20"
        >
          {isLoading ? (
            <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Adding Account...</>
          ) : (
            <>Add GCP Account <Icon icon="mdi:check-circle-outline" width={16} /></>
          )}
        </button>
      </form>
    </div>
  );
};

export default GCPForm;
