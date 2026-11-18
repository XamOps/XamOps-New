/**
 * Allowlist tab on `/autospotting-admin` (plan-phase-15 task 15.4, design-core.md section 6.1, DC-11).
 * An account joins the conversion allowlist only with a billing basis: "Client contract", offered only
 * while the account's client has a contract in force, or "Never bills", with a required reason. The
 * list shows each row's basis and whether its contract is still in force. Super admins and XamOps
 * admins only; the server refuses everyone else. Rows record who may convert. Nothing here bills.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AutoSpottingApiError,
  addToAllowlist,
  getAllowlistOptions,
  listAllowlist,
  newIdempotencyKey,
  removeFromAllowlist,
  type AllowlistOptions,
  type AllowlistRow,
  type BillingBasis,
} from '../../api/autospottingApi';
const MAX_REASON = 500;
const when = (iso: string | null): string => (iso ? iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC') : '');
const BASIS_LABEL: Record<BillingBasis, string> = { CONTRACT: 'Client contract', NO_FEE: 'Never bills' };
function messageFor(e: unknown): string {
  if (e instanceof AutoSpottingApiError) {
    if (e.status === 403) return 'You are not allowed to change this.';
    if (e.code === 'ALLOWLIST_NEEDS_BILLING_BASIS') return e.detail || 'A billing basis is needed.';
    if (e.status === 404) return 'Account not found.';
    return e.detail || 'The change failed. Try again.';
  }
  return 'The change failed. Try again.';
}
const AllowlistPanel: React.FC = () => {
  const [rows, setRows] = useState<AllowlistRow[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [accountText, setAccountText] = useState('');
  const [options, setOptions] = useState<AllowlistOptions | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [basis, setBasis] = useState<BillingBasis | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<number | null>(null);
  const [removeReason, setRemoveReason] = useState('');
  // One idempotency key per user action, reused if the same action is retried.
  const keyRef = useRef<string | null>(null);
  const load = useCallback(async () => {
    setLoadError(null);
    try {
      setRows(await listAllowlist());
    } catch (e) {
      setLoadError(messageFor(e).replace('change', 'read'));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const accountId = /^[1-9]\d{0,17}$/.test(accountText.trim()) ? Number(accountText.trim()) : null;
  const lookup = async () => {
    setOptions(null);
    setBasis(null);
    setLookupError(null);
    if (accountId === null) {
      setLookupError('Enter an account id.');
      return;
    }
    try {
      const o = await getAllowlistOptions(accountId);
      setOptions(o);
      keyRef.current = newIdempotencyKey();
    } catch (e) {
      setLookupError(messageFor(e));
    }
  };
  const reasonOk = reason.trim().length > 0 && reason.length <= MAX_REASON;
  const canAdd = options !== null && basis !== null && reasonOk && !busy;
  const add = async () => {
    if (!options || !basis) return;
    setBusy(true);
    setFormError(null);
    try {
      await addToAllowlist(
        { cloudAccountId: options.cloudAccountId, billingBasis: basis, reason: reason.trim() },
        keyRef.current ?? newIdempotencyKey(),
      );
      setNotice(`Account ${options.cloudAccountId} added: ${BASIS_LABEL[basis]}.`);
      setAccountText('');
      setOptions(null);
      setBasis(null);
      setReason('');
      await load();
    } catch (e) {
      setFormError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };
  const remove = async (id: number) => {
    setBusy(true);
    setFormError(null);
    try {
      await removeFromAllowlist(id, removeReason.trim(), keyRef.current ?? newIdempotencyKey());
      setNotice(`Account ${id} removed.`);
      setRemoving(null);
      setRemoveReason('');
      await load();
    } catch (e) {
      setFormError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-label="Allowlist" className="space-y-5">
      {notice && (
        <p role="status" className="rounded border border-gray-400 bg-gray-100 px-3 py-2 text-sm text-black">
          {notice}
        </p>
      )}
      {loadError && (
        <p role="alert" className="rounded border border-gray-400 px-3 py-2 text-sm text-black">
          {loadError}
        </p>
      )}
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5">
        <h3 className="mb-3 text-[14px] font-bold text-black">Allowlist</h3>
        {rows === null ? (
          <p className="text-sm text-gray-600">Loading allowlist.</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-gray-600">No accounts on the allowlist.</p>
        ) : (
          <table className="w-full text-left text-sm text-black">
            <thead className="text-xs text-gray-600">
              <tr>
                <th className="py-1 pr-3">Account</th>
                <th className="py-1 pr-3">Cloud</th>
                <th className="py-1 pr-3">Basis</th>
                <th className="py-1 pr-3">Contract</th>
                <th className="py-1 pr-3">Reason</th>
                <th className="py-1 pr-3">Added</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.cloudAccountId} className="border-t border-gray-200">
                  <td className="py-2 pr-3">
                    <span className="font-medium">{r.accountName ?? 'Unknown'}</span>
                    <span className="ml-2 font-mono text-xs text-gray-600">{r.cloudAccountId}</span>
                  </td>
                  <td className="py-2 pr-3">{r.provider}</td>
                  <td className="py-2 pr-3">{BASIS_LABEL[r.billingBasis] ?? r.billingBasis}</td>
                  <td className="py-2 pr-3">
                    {r.billingBasis === 'CONTRACT' ? (r.contractInForce ? 'In force' : 'Ended') : 'None'}
                  </td>
                  <td className="py-2 pr-3 text-gray-600">{r.reason}</td>
                  <td className="py-2 pr-3 text-gray-600">{`${when(r.addedAt)} by ${r.addedBy}`}</td>
                  <td className="py-2">
                    <button
                      type="button"
                      onClick={() => {
                        keyRef.current = newIdempotencyKey();
                        setRemoving(r.cloudAccountId);
                        setRemoveReason('');
                        setFormError(null);
                      }}
                      className="rounded border border-black px-3 py-1"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {removing !== null && (
          <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
            <input
              aria-label="Removal reason"
              value={removeReason}
              maxLength={MAX_REASON}
              onChange={e => setRemoveReason(e.target.value)}
              placeholder="Reason"
              className="w-72 rounded border border-gray-400 px-2 py-1 text-black"
            />
            <button
              type="button"
              disabled={removeReason.trim().length === 0 || busy}
              onClick={() => void remove(removing)}
              className="rounded border border-black bg-black px-3 py-1 text-white disabled:border-gray-300 disabled:bg-gray-300"
            >
              {`Remove ${removing}`}
            </button>
            <button type="button" onClick={() => setRemoving(null)} className="rounded border border-gray-400 px-3 py-1 text-black">
              Cancel
            </button>
            {formError && <span role="alert">{formError}</span>}
          </div>
        )}
      </div>
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5">
        <h3 className="mb-3 text-[14px] font-bold text-black">Add account</h3>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <input
            aria-label="Account id"
            value={accountText}
            inputMode="numeric"
            onChange={e => {
              setAccountText(e.target.value);
              setOptions(null);
              setBasis(null);
            }}
            placeholder="Account id"
            className="w-48 rounded border border-gray-400 px-2 py-1 text-black"
          />
          <button type="button" onClick={() => void lookup()} className="rounded border border-black px-3 py-1">
            Find
          </button>
          {lookupError && <span role="alert">{lookupError}</span>}
        </div>
        {options && (
          <fieldset className="mt-3 space-y-2 text-sm text-black">
            <legend className="mb-1 text-gray-600">
              {`${options.accountName ?? 'Account'} (${options.provider}, id ${options.cloudAccountId})`}
            </legend>
            {options.contractInForce && (
              <label className="flex items-center gap-2">
                <input
                  type="radio"
                  name="billing-basis"
                  checked={basis === 'CONTRACT'}
                  onChange={() => setBasis('CONTRACT')}
                />
                Client contract
              </label>
            )}
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="billing-basis"
                checked={basis === 'NO_FEE'}
                onChange={() => setBasis('NO_FEE')}
              />
              Never bills
            </label>
            {!options.contractInForce && <p className="text-gray-600">No contract in force for this client.</p>}
            <div className="flex flex-wrap items-center gap-2">
              <input
                aria-label="Reason"
                value={reason}
                maxLength={MAX_REASON}
                onChange={e => setReason(e.target.value)}
                placeholder="Reason"
                className="w-72 rounded border border-gray-400 px-2 py-1 text-black"
              />
              <button
                type="button"
                disabled={!canAdd}
                onClick={() => void add()}
                className="rounded border border-black bg-black px-3 py-1 text-white disabled:border-gray-300 disabled:bg-gray-300"
              >
                Add to allowlist
              </button>
              {formError && removing === null && <span role="alert">{formError}</span>}
            </div>
          </fieldset>
        )}
      </div>
    </section>
  );
};
export default AllowlistPanel;
