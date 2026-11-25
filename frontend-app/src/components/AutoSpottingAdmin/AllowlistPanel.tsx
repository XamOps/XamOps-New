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
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY, INPUT, TABLE, TD_SM, TH_SM, TR } from '../../pages/autospotting/components/theme';
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
        <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-[13px] text-emerald-900">
          {notice}
        </p>
      )}
      {loadError && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">
          {loadError}
        </p>
      )}
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Allowlist</h3>
        {rows === null ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Loading allowlist.</p>
        ) : rows.length === 0 ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No accounts on the allowlist.</p>
        ) : (
          <table className={TABLE}>
            <thead className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <tr>
                <th className={TH_SM}>Account</th>
                <th className={TH_SM}>Cloud</th>
                <th className={TH_SM}>Basis</th>
                <th className={TH_SM}>Contract</th>
                <th className={TH_SM}>Reason</th>
                <th className={TH_SM}>Added</th>
                <th className={TH_SM} />
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.cloudAccountId} className={TR}>
                  <td className={TD_SM}>
                    <span className="font-medium">{r.accountName ?? 'Unknown'}</span>
                    <span className="ml-2 font-mono text-[11px] text-[rgba(0,0,0,0.55)]">{r.cloudAccountId}</span>
                  </td>
                  <td className={TD_SM}>{r.provider}</td>
                  <td className={TD_SM}>{BASIS_LABEL[r.billingBasis] ?? r.billingBasis}</td>
                  <td className={TD_SM}>
                    {r.billingBasis === 'CONTRACT' ? (r.contractInForce ? 'In force' : 'Ended') : 'None'}
                  </td>
                  <td className={`${TD_SM} text-[rgba(0,0,0,0.55)]`}>{r.reason}</td>
                  <td className={`${TD_SM} text-[rgba(0,0,0,0.55)]`}>{`${when(r.addedAt)} by ${r.addedBy}`}</td>
                  <td className={TD_SM}>
                    <button
                      type="button"
                      onClick={() => {
                        keyRef.current = newIdempotencyKey();
                        setRemoving(r.cloudAccountId);
                        setRemoveReason('');
                        setFormError(null);
                      }}
                      className={BTN_SECONDARY}
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
          <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
            <input
              aria-label="Removal reason"
              value={removeReason}
              maxLength={MAX_REASON}
              onChange={e => setRemoveReason(e.target.value)}
              placeholder="Reason"
              className={`w-72 ${INPUT}`}
            />
            <button
              type="button"
              disabled={removeReason.trim().length === 0 || busy}
              onClick={() => void remove(removing)}
              className={BTN_DANGER}
            >
              {`Remove ${removing}`}
            </button>
            <button type="button" onClick={() => setRemoving(null)} className={BTN_SECONDARY}>
              Cancel
            </button>
            {formError && <span role="alert" className="text-red-700">{formError}</span>}
          </div>
        )}
      </div>
      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Add account</h3>
        <div className="flex flex-wrap items-center gap-2 text-[13px]">
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
            className={`w-48 ${INPUT}`}
          />
          <button type="button" onClick={() => void lookup()} className={BTN_SECONDARY}>
            Find
          </button>
          {lookupError && <span role="alert" className="text-red-700">{lookupError}</span>}
        </div>
        {options && (
          <fieldset className="mt-3 space-y-2 text-[13px] text-[rgba(0,0,0,0.88)]">
            <legend className="mb-1 text-[rgba(0,0,0,0.55)]">
              {`${options.accountName ?? 'Account'} (${options.provider}, id ${options.cloudAccountId})`}
            </legend>
            {options.contractInForce && (
              <label className="flex items-center gap-2">
                <input
                  type="radio"
                  className="h-4 w-4 accent-indigo-600"
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
                className="h-4 w-4 accent-indigo-600"
                name="billing-basis"
                checked={basis === 'NO_FEE'}
                onChange={() => setBasis('NO_FEE')}
              />
              Never bills
            </label>
            {!options.contractInForce && <p className="text-[rgba(0,0,0,0.55)]">No contract in force for this client.</p>}
            <div className="flex flex-wrap items-center gap-2">
              <input
                aria-label="Reason"
                value={reason}
                maxLength={MAX_REASON}
                onChange={e => setReason(e.target.value)}
                placeholder="Reason"
                className={`w-72 ${INPUT}`}
              />
              <button
                type="button"
                disabled={!canAdd}
                onClick={() => void add()}
                className={BTN_PRIMARY}
              >
                Add to allowlist
              </button>
              {formError && removing === null && <span role="alert" className="text-red-700">{formError}</span>}
            </div>
          </fieldset>
        )}
      </div>
    </section>
  );
};
export default AllowlistPanel;
