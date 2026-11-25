/**
 * BillOps admin area for AutoSpotting billing (plan-phase-15 task 15.3, ledger design section 11.4,
 * ux section 2.5). Phase 15 ships two tabs, Contracts and Go-live. Both are records only: nothing is
 * invoiced while billing stays off. BillOps admins and super admins only; the server refuses everyone
 * else and refuses writes in an impersonated session.
 *
 * Percentages and amounts are kept as decimal text end to end, never as JS numbers.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AutoSpottingApiError,
  createContract,
  endContract,
  isDecimalText,
  listContracts,
  listGoLives,
  newIdempotencyKey,
  setGoLive,
  type ContractRow,
  type GoLiveRow,
} from '../../api/autospottingApi';
import { AriaTabGroup } from '../../components/ui';
import { useOptionalTopbarActions } from '../../contexts/TopbarContext';
import { BTN_DANGER, BTN_PRIMARY, BTN_SECONDARY, INPUT, TABLE, TD_SM, TH_SM, TR } from '../autospotting/components/theme';
const MAX_REASON = 500;
const PROVIDERS = ['AWS', 'GCP'] as const;
const when = (iso: string | null): string => (iso ? iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC') : 'Open');
/** A datetime-local value to a UTC instant, or null when blank. */
const toInstant = (local: string): string | null => (local.trim() ? new Date(local).toISOString() : null);
function messageFor(e: unknown): string {
  if (e instanceof AutoSpottingApiError) {
    if (e.status === 403) return 'You are not allowed to change this.';
    if (e.code === 'CONTRACT_OVERLAP') return 'This overlaps another contract of the client.';
    if (e.code === 'GOLIVE_ALREADY_SET') return 'Go-live is already set for this cloud.';
    if (e.code === 'CONTRACT_CANNOT_EXTEND') return 'A contract can only be ended earlier.';
    return e.detail || 'The change failed. Try again.';
  }
  return 'The change failed. Try again.';
}
const state = (c: ContractRow): string => {
  if (c.inForce) return 'In force';
  return Date.parse(c.effectiveFrom) > Date.now() ? 'Scheduled' : 'Ended';
};
const inputClass = INPUT;
const primaryButton = BTN_PRIMARY;
const CARD_CLASS = 'rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm';
const H3 = 'mb-3 text-[14px] font-bold text-[rgba(0,0,0,0.88)]';
const ContractsTab: React.FC = () => {
  const [clientText, setClientText] = useState('');
  const [clientId, setClientId] = useState<number | null>(null);
  const [contracts, setContracts] = useState<ContractRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // New contract form. The discount has no preselection: it must be chosen.
  const [share, setShare] = useState('');
  const [cap, setCap] = useState('');
  const [declaration, setDeclaration] = useState<'DECLARED' | 'NOT_DECLARED' | null>(null);
  const [discount, setDiscount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  // End contract.
  const [ending, setEnding] = useState<string | null>(null);
  const [endAt, setEndAt] = useState('');
  const [endReason, setEndReason] = useState('');
  const keyRef = useRef<string>(newIdempotencyKey());
  const load = useCallback(async (id: number) => {
    setError(null);
    try {
      setContracts(await listContracts(id));
    } catch (e) {
      setContracts(null);
      setError(messageFor(e).replace('change', 'read'));
    }
  }, []);
  const find = () => {
    const t = clientText.trim();
    if (!/^[1-9]\d{0,17}$/.test(t)) {
      setError('Enter a client id.');
      return;
    }
    setClientId(Number(t));
    setNotice(null);
    void load(Number(t));
  };
  useEffect(() => {
    keyRef.current = newIdempotencyKey();
  }, [clientId]);
  const shareOk = isDecimalText(share) && Number(share) > 0 && Number(share) <= 100;
  const capOk = cap === '' || (isDecimalText(cap) && Number(cap) > 0 && /^\d+(\.\d{1,2})?$/.test(cap));
  const discountOk =
    declaration === 'NOT_DECLARED' || (declaration === 'DECLARED' && isDecimalText(discount) && Number(discount) < 100);
  const reasonOk = reason.trim().length > 0 && reason.length <= MAX_REASON;
  const canCreate = clientId !== null && shareOk && capOk && discountOk && reasonOk && !busy;
  const create = async () => {
    if (clientId === null || declaration === null) return;
    setBusy(true);
    setError(null);
    try {
      await createContract(
        clientId,
        {
          sharePct: share,
          monthlyCap: cap === '' ? null : cap,
          discountDeclaration: declaration,
          declaredDiscountPct: declaration === 'DECLARED' ? discount : null,
          effectiveFrom: toInstant(from),
          effectiveTo: toInstant(to),
          reason: reason.trim(),
        },
        keyRef.current,
      );
      setNotice('Contract created.');
      setShare('');
      setCap('');
      setDeclaration(null);
      setDiscount('');
      setFrom('');
      setTo('');
      setReason('');
      keyRef.current = newIdempotencyKey();
      await load(clientId);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };
  const end = async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      await endContract(id, { effectiveTo: toInstant(endAt), reason: endReason.trim() }, keyRef.current);
      setNotice('Contract ended.');
      setEnding(null);
      setEndAt('');
      setEndReason('');
      keyRef.current = newIdempotencyKey();
      if (clientId !== null) await load(clientId);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-label="Contracts" className="space-y-5">
      {notice && (
        <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-[13px] text-emerald-900">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">
          {error}
        </p>
      )}
      <div className={CARD_CLASS}>
        <h3 className={H3}>Contracts</h3>
        <div className="flex flex-wrap items-center gap-2 text-[13px]">
          <input
            aria-label="Client id"
            value={clientText}
            inputMode="numeric"
            onChange={e => setClientText(e.target.value)}
            placeholder="Client id"
            className={`w-40 ${inputClass}`}
          />
          <button type="button" onClick={find} className={BTN_SECONDARY}>
            Load
          </button>
        </div>
        {contracts !== null &&
          (contracts.length === 0 ? (
            <p className="mt-3 text-[13px] text-[rgba(0,0,0,0.55)]">No contracts for this client.</p>
          ) : (
            <table className={`mt-3 ${TABLE}`}>
              <thead className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
                <tr>
                  <th className={TH_SM}>Share %</th>
                  <th className={TH_SM}>Monthly cap (USD)</th>
                  <th className={TH_SM}>Discount</th>
                  <th className={TH_SM}>From</th>
                  <th className={TH_SM}>To</th>
                  <th className={TH_SM}>State</th>
                  <th className={TH_SM} />
                </tr>
              </thead>
              <tbody>
                {contracts.map(c => (
                  <tr key={c.id} className={TR}>
                    <td className={TD_SM}>{c.sharePct}</td>
                    <td className={TD_SM}>{c.monthlyCap ? c.monthlyCap.amount : 'None'}</td>
                    <td className={TD_SM}>
                      {c.discountDeclaration === 'DECLARED' ? `Declared ${c.declaredDiscountPct}%` : 'Not declared'}
                    </td>
                    <td className={`${TD_SM} whitespace-nowrap`}>{when(c.effectiveFrom)}</td>
                    <td className={`${TD_SM} whitespace-nowrap`}>{when(c.effectiveTo)}</td>
                    <td className={TD_SM}>
                      <span
                        className={`inline-block rounded-full border px-2 py-0.5 text-[11px] font-semibold ${
                          state(c) === 'In force'
                            ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                            : state(c) === 'Scheduled'
                              ? 'border-indigo-200 bg-indigo-50 text-indigo-700'
                              : 'border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.6)]'
                        }`}
                      >
                        {state(c)}
                      </span>
                    </td>
                    <td className={TD_SM}>
                      {state(c) !== 'Ended' && (
                        <button
                          type="button"
                          onClick={() => {
                            keyRef.current = newIdempotencyKey();
                            setEnding(c.id);
                          }}
                          className={BTN_SECONDARY}
                        >
                          End contract
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        {ending !== null && (
          <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
            <input
              aria-label="End at"
              type="datetime-local"
              value={endAt}
              onChange={e => setEndAt(e.target.value)}
              className={inputClass}
            />
            <span className="text-[rgba(0,0,0,0.55)]">Blank ends it now.</span>
            <input
              aria-label="End reason"
              value={endReason}
              maxLength={MAX_REASON}
              onChange={e => setEndReason(e.target.value)}
              placeholder="Reason"
              className={`w-72 ${inputClass}`}
            />
            <button
              type="button"
              disabled={endReason.trim().length === 0 || busy}
              onClick={() => void end(ending)}
              className={BTN_DANGER}
            >
              Confirm end
            </button>
            <button type="button" onClick={() => setEnding(null)} className={BTN_SECONDARY}>
              Cancel
            </button>
          </div>
        )}
      </div>
      <div className={CARD_CLASS}>
        <h3 className={H3}>New contract</h3>
        {clientId === null ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Load a client first.</p>
        ) : (
          <div className="space-y-3 text-[13px] text-[rgba(0,0,0,0.88)]">
            <div className="flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-2">
                Share %
                <input aria-label="Share %" value={share} inputMode="decimal" onChange={e => setShare(e.target.value)} className={`w-28 ${inputClass}`} />
              </label>
              <label className="flex items-center gap-2">
                Monthly cap (USD)
                <input aria-label="Monthly cap (USD)" value={cap} inputMode="decimal" onChange={e => setCap(e.target.value)} placeholder="No cap" className={`w-32 ${inputClass}`} />
              </label>
            </div>
            <fieldset className="space-y-1">
              <legend className="text-[rgba(0,0,0,0.55)]">Discount</legend>
              <label className="flex items-center gap-2">
                <input type="radio" className="h-4 w-4 accent-indigo-600" name="discount" checked={declaration === 'DECLARED'} onChange={() => setDeclaration('DECLARED')} />
                Discount declared
                <input
                  aria-label="Declared discount %"
                  value={discount}
                  inputMode="decimal"
                  disabled={declaration !== 'DECLARED'}
                  onChange={e => setDiscount(e.target.value)}
                  placeholder="0 means none"
                  className={`w-32 ${inputClass} disabled:bg-[rgba(0,0,0,0.04)]`}
                />
              </label>
              <label className="flex items-center gap-2">
                <input type="radio" className="h-4 w-4 accent-indigo-600" name="discount" checked={declaration === 'NOT_DECLARED'} onChange={() => setDeclaration('NOT_DECLARED')} />
                Not declared
              </label>
            </fieldset>
            <div className="flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-2">
                From
                <input aria-label="Effective from" type="datetime-local" value={from} onChange={e => setFrom(e.target.value)} className={inputClass} />
              </label>
              <label className="flex items-center gap-2">
                To
                <input aria-label="Effective to" type="datetime-local" value={to} onChange={e => setTo(e.target.value)} className={inputClass} />
              </label>
              <span className="text-[rgba(0,0,0,0.55)]">Blank from means now. Never in the past.</span>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <input aria-label="Reason" value={reason} maxLength={MAX_REASON} onChange={e => setReason(e.target.value)} placeholder="Reason" className={`w-72 ${inputClass}`} />
              <button type="button" disabled={!canCreate} onClick={() => void create()} className={primaryButton}>
                Create contract
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
};
const GoLiveTab: React.FC = () => {
  const [rows, setRows] = useState<GoLiveRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [setting, setSetting] = useState<string | null>(null);
  const [at, setAt] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const keyRef = useRef<string>(newIdempotencyKey());
  const load = useCallback(async () => {
    try {
      setRows(await listGoLives());
    } catch (e) {
      setError(messageFor(e).replace('change', 'read'));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const confirm = async (provider: string) => {
    setBusy(true);
    setError(null);
    try {
      await setGoLive(provider, { goLiveAt: toInstant(at), reason: reason.trim() }, keyRef.current);
      setNotice(`${provider} go-live set.`);
      setSetting(null);
      setAt('');
      setReason('');
      keyRef.current = newIdempotencyKey();
      await load();
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-label="Go-live" className="space-y-5">
      {notice && (
        <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-[13px] text-emerald-900">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">
          {error}
        </p>
      )}
      <div className={CARD_CLASS}>
        <h3 className={H3}>Go-live</h3>
        {rows === null ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Loading go-live.</p>
        ) : (
          <table className={TABLE}>
            <thead className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <tr>
                <th className={TH_SM}>Cloud</th>
                <th className={TH_SM}>Go-live</th>
                <th className={TH_SM}>Set by</th>
                <th className={TH_SM} />
              </tr>
            </thead>
            <tbody>
              {PROVIDERS.map(p => {
                const row = rows.find(r => r.provider === p);
                return (
                  <tr key={p} className={TR}>
                    <td className={`${TD_SM} font-medium`}>{p}</td>
                    <td className={TD_SM}>{row ? when(row.goLiveAt) : 'Not set'}</td>
                    <td className={`${TD_SM} text-[rgba(0,0,0,0.55)]`}>{row ? row.setBy : ''}</td>
                    <td className={TD_SM}>
                      {!row && (
                        <button
                          type="button"
                          onClick={() => {
                            keyRef.current = newIdempotencyKey();
                            setSetting(p);
                          }}
                          className={BTN_SECONDARY}
                        >
                          {`Set ${p} go-live`}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        {setting !== null && (
          <div className="mt-3 space-y-2 text-[13px] text-[rgba(0,0,0,0.88)]">
            <p className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900">
              No billable entry will cover usage before this instant, and nothing is back-filled. It is set once
              and cannot be changed.
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <input aria-label="Go-live at" type="datetime-local" value={at} onChange={e => setAt(e.target.value)} className={inputClass} />
              <span className="text-[rgba(0,0,0,0.55)]">Blank sets it now.</span>
              <input aria-label="Reason" value={reason} maxLength={MAX_REASON} onChange={e => setReason(e.target.value)} placeholder="Reason" className={`w-72 ${inputClass}`} />
              <button
                type="button"
                disabled={reason.trim().length === 0 || busy}
                onClick={() => void confirm(setting)}
                className={primaryButton}
              >
                {`Confirm ${setting} go-live`}
              </button>
              <button type="button" onClick={() => setSetting(null)} className={BTN_SECONDARY}>
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
};
const BillingHeader: React.FC = () => {
  const topbar = useOptionalTopbarActions();
  useEffect(() => {
    if (!topbar) return undefined;
    topbar.setActions(
      <div className="flex items-center gap-2">
        <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-indigo-500" />
        <span className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.55)]">AutoSpotting billing</span>
      </div>,
    );
    return () => topbar.clearActions();
  }, [topbar]);
  return null;
};
const AutoSpottingBillingAdminPage: React.FC = () => {
  const [tab, setTab] = useState<'contracts' | 'golive'>('contracts');
  return (
    <main className="flex-1 px-6 pt-4 pb-6">
      <BillingHeader />
      <AriaTabGroup
        tabs={[
          { id: 'contracts', label: 'Contracts', icon: 'mdi:file-sign' },
          { id: 'golive', label: 'Go-live', icon: 'mdi:rocket-launch-outline' },
        ]}
        selectedKey={tab}
        onSelectionChange={k => setTab(k as 'contracts' | 'golive')}
        label="AutoSpotting billing sections"
        className="mb-5"
      />
      {tab === 'contracts' ? <ContractsTab /> : <GoLiveTab />}
    </main>
  );
};
export default AutoSpottingBillingAdminPage;
