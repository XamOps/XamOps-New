import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { DspmMappingAdminView, DspmMappingRequest } from '../../types/dspm';

const FormLabel: React.FC<{ icon: string; text: string; hint?: string }> = ({ icon, text, hint }) => (
  <label className="flex items-center gap-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
    <Icon icon={icon} width={11} />
    {text}
    {hint && <span className="normal-case tracking-normal font-normal text-[rgba(0,0,0,0.35)]">{hint}</span>}
  </label>
);

const FieldInput: React.FC<React.InputHTMLAttributes<HTMLInputElement>> = (props) => (
  <input
    {...props}
    className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60 transition-colors"
  />
);

interface Props {
  clientId: number;
  clientName: string | null;
  /** Null when creating. */
  existing: DspmMappingAdminView | null;
  saving: boolean;
  onCancel: () => void;
  onSave: (request: DspmMappingRequest) => Promise<void>;
}

/**
 * The mapping editor: project id, name, base URL, and the write-only API key.
 *
 * Two behaviours here are load-bearing rather than cosmetic.
 *
 * **The key field is never pre-filled.** On edit it renders empty with a placeholder showing the stored
 * key's last four characters, and blank means "keep the existing key". Anything else would require
 * sending a live credential to the browser on every page load, and treating blank as "clear" would wipe
 * a working connection every time someone corrected a typo in the project name.
 *
 * **Repointing an existing mapping to a different project requires a second confirmation.** Changing
 * that one field swaps the customer's entire dataset — every datastore, every record count, every
 * finding — and it is the only field here that can show one customer another customer's data. A quiet
 * text edit is the wrong affordance for that.
 */
const DspmMappingModal: React.FC<Props> = ({
  clientId, clientName, existing, saving, onCancel, onSave,
}) => {
  const isEdit = existing !== null;

  const [projectId, setProjectId] = useState(existing?.mattersProjectId ?? '');
  const [projectName, setProjectName] = useState(existing?.mattersProjectName ?? '');
  const [accountId, setAccountId] = useState(existing?.mattersAccountId ?? '');
  const [baseUrl, setBaseUrl] = useState(existing?.baseUrl ?? 'https://app.matters.ai');
  const [apiKey, setApiKey] = useState('');
  const [expiresAt, setExpiresAt] = useState(
    existing?.apiKeyExpiresAt ? existing.apiKeyExpiresAt.slice(0, 10) : '');
  const [enabled, setEnabled] = useState(existing?.enabled ?? true);
  const [error, setError] = useState<string | null>(null);
  const [confirmRepoint, setConfirmRepoint] = useState(false);

  const repointing = isEdit
    && existing!.mattersProjectId !== projectId.trim()
    && projectId.trim().length > 0;

  // A changed project id re-arms the confirmation, so editing the field again after confirming does not
  // sail through on the previous acknowledgement.
  useEffect(() => { setConfirmRepoint(false); }, [projectId]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!projectId.trim()) { setError('A Matters project ID is required.'); return; }
    if (!baseUrl.trim()) { setError('A base URL is required.'); return; }
    if (!isEdit && !apiKey.trim()) { setError('An API key is required when creating a mapping.'); return; }

    // Caught here as well as server-side: the shape is obvious enough that failing before a round trip
    // is kinder, and the server message would arrive as a generic 400.
    if (apiKey.trim() && !/^mts_[^_]+_[^_]+_.+$/.test(apiKey.trim())) {
      setError('That does not look like a Matters API key. Expected mts_<env>_<key_id>_<secret>.');
      return;
    }
    if (repointing && !confirmRepoint) {
      setError('Repointing this mapping will replace everything this customer sees. '
        + 'Tick the confirmation below to continue.');
      return;
    }

    try {
      await onSave({
        mattersProjectId: projectId.trim(),
        mattersProjectName: projectName.trim() || null,
        mattersAccountId: accountId.trim() || null,
        baseUrl: baseUrl.trim(),
        // Omitted entirely when blank, so the backend's "keep existing" path is taken rather than
        // receiving an empty string it would have to interpret.
        apiKey: apiKey.trim() || null,
        apiKeyExpiresAt: expiresAt ? new Date(`${expiresAt}T00:00:00Z`).toISOString() : null,
        enabled,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  return ReactDOM.createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" onClick={onCancel} />
      <div
        className="relative z-10 w-full max-w-xl max-h-[90vh] overflow-y-auto bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-xl"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center gap-3 px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
          <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.88)] flex items-center justify-center flex-shrink-0">
            <Icon icon={isEdit ? 'mdi:database-edit-outline' : 'mdi:database-plus-outline'} width={20} color="white" />
          </div>
          <div className="min-w-0">
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
              {isEdit ? 'Edit DSPM mapping' : 'Add DSPM mapping'}
            </h2>
            <p className="text-xs text-[rgba(0,0,0,0.4)] truncate">
              {clientName ?? `Client ${clientId}`}
            </p>
          </div>
          <button
            onClick={onCancel}
            className="ml-auto p-2 rounded-lg hover:bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.4)] transition-colors"
            aria-label="Close"
          >
            <Icon icon="mdi:close" width={16} />
          </button>
        </div>

        <form onSubmit={submit} className="p-6 space-y-4">
          {error && (
            <div className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-100 rounded-lg text-xs text-rose-700">
              <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0 mt-0.5" />
              <span>{error}</span>
            </div>
          )}

          <div>
            <FormLabel icon="mdi:folder-outline" text="Matters project ID" />
            <FieldInput
              type="text" value={projectId} required
              placeholder="6a9ff4d21192a9215c6986e5"
              onChange={e => setProjectId(e.target.value)}
              className=""
            />
            <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">
              From Select Project in the Matters console. One project serves exactly one customer.
            </p>
          </div>

          {repointing && (
            <label className="flex items-start gap-2 p-3 bg-amber-50 border border-amber-200 rounded-lg cursor-pointer">
              <input
                type="checkbox" checked={confirmRepoint}
                onChange={e => setConfirmRepoint(e.target.checked)}
                className="mt-0.5 accent-amber-600"
              />
              <span className="text-xs text-amber-800">
                <strong className="font-semibold">This changes which project the customer sees.</strong>{' '}
                Their entire inventory, record counts and findings will be replaced by
                project <span className="font-mono">{projectId.trim()}</span>. Confirm this is intended.
              </span>
            </label>
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <FormLabel icon="mdi:label-outline" text="Project name" hint="optional" />
              <FieldInput
                type="text" value={projectName} placeholder="Acme Production"
                onChange={e => setProjectName(e.target.value)}
              />
            </div>
            <div>
              <FormLabel icon="mdi:office-building-outline" text="Account ID" hint="optional" />
              <FieldInput
                type="text" value={accountId} placeholder="Matters account id"
                onChange={e => setAccountId(e.target.value)}
              />
            </div>
          </div>

          <div>
            <FormLabel icon="mdi:web" text="Base URL" />
            <FieldInput
              type="url" value={baseUrl} required
              placeholder="https://app.matters.ai"
              onChange={e => setBaseUrl(e.target.value)}
            />
            <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">
              Must match the key's region. A key issued for one environment is rejected against another.
            </p>
          </div>

          <div>
            <FormLabel
              icon="mdi:key-outline"
              text="API key"
              hint={isEdit ? 'leave blank to keep the current key' : undefined}
            />
            <FieldInput
              type="password"
              value={apiKey}
              autoComplete="off"
              required={!isEdit}
              placeholder={isEdit && existing?.apiKeyLastFour
                ? `Currently ····${existing.apiKeyLastFour}`
                : 'mts_prod_<key_id>_<secret>'}
              onChange={e => setApiKey(e.target.value)}
            />
            <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">
              Stored encrypted and never shown again. Matters displays a key once at creation, so keep
              your own copy until you have verified the connection.
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <FormLabel icon="mdi:calendar-clock" text="Key expires" hint="optional" />
              <FieldInput
                type="date" value={expiresAt}
                onChange={e => setExpiresAt(e.target.value)}
              />
              <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">
                Matters caps key lifetime at 180 days. Recording it enables the expiry warning.
              </p>
            </div>
            <div>
              <FormLabel icon="mdi:toggle-switch-outline" text="Connection" />
              <label className="flex items-center gap-2 px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg cursor-pointer">
                <input
                  type="checkbox" checked={enabled}
                  onChange={e => setEnabled(e.target.checked)}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                <span className="text-[rgba(0,0,0,0.7)]">Enabled</span>
              </label>
              <p className="mt-1 text-[11px] text-[rgba(0,0,0,0.4)]">
                Separate from the add-on entitlement. Disable to park a connection without changing
                what the customer has bought.
              </p>
            </div>
          </div>

          <div className="flex items-center justify-end gap-2 pt-2">
            <button
              type="button" onClick={onCancel}
              className="px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.6)] rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit" disabled={saving}
              className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold text-white bg-[rgba(0,0,0,0.88)] rounded-lg hover:bg-black disabled:opacity-50 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.3)]"
            >
              {saving && <Icon icon="mdi:loading" width={13} className="animate-spin" />}
              {isEdit ? 'Save mapping' : 'Create mapping'}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
};

export default DspmMappingModal;
