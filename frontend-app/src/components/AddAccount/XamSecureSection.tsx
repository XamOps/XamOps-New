/**
 * Security Monitoring section on the Add Account page.
 *
 * Enables XamSecure (cloud security posture, vulnerabilities and runtime threats) on an AWS
 * account the customer has already connected to XamOps. Picking a provider expands the list of
 * that provider's accounts with their current connection state.
 *
 * White-labelled deliberately: the vendor behind this is never named in customer-facing copy.
 * The CloudFormation stack itself is the vendor's own artifact, so the name is visible at the
 * infrastructure level, but nothing here says it.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchOnboardingAccounts, useUpwindOnboarding } from '../../hooks/useUpwindOnboarding';
import SensorDeployPanel from '../XamSecure/SensorDeployPanel';
import KubernetesSensorPanel from '../XamSecure/KubernetesSensorPanel';
import EcsSensorPanel from '../XamSecure/EcsSensorPanel';
import type { OnboardingAccount } from '../../types/xamsecure';

const STATUS_LABEL: Record<string, string> = {
  CONNECTED: 'Protected',
  PENDING: 'Waiting for first scan',
  NOT_CONNECTED: 'Not protected',
};

/** Only AWS has a self-service path; the others need the vendor's own per-provider flow. */
const PROVIDERS: { id: string; name: string; iconImg?: string; icon?: string; supported: boolean }[] = [
  { id: 'AWS', name: 'AWS', iconImg: '/icons/aws-icon.png', supported: true },
  { id: 'GCP', name: 'GCP', iconImg: '/icons/gcp-icon.png', supported: false },
  { id: 'Azure', name: 'Azure', iconImg: '/icons/azure-icon.png', supported: false },
];

const XamSecureSection: React.FC = () => {
  const [accounts, setAccounts] = useState<OnboardingAccount[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setAccounts(await fetchOnboardingAccounts());
      setLoadError(null);
    } catch (err) {
      // A failure here means "we could not read status", not "you have no accounts" — saying the
      // latter sends people to add an account they already have, which is exactly what this
      // section did when the request failed with a blank message: the error <p> rendered empty
      // and the empty-state copy showed instead. Never trust the message to be non-empty.
      const detail = err instanceof Error && err.message ? err.message : String(err ?? '');
      setLoadError(detail && detail !== 'undefined'
        ? `Could not load security monitoring status: ${detail}`
        : 'Could not load security monitoring status. Check that you are still signed in, '
          + 'then reload.');
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const { busyId, checks, errors, disconnects, connect, check, disconnect } =
    useUpwindOnboarding(load);
  const [confirmId, setConfirmId] = useState<string | null>(null);

  const forProvider = useMemo(
    () => (accounts ?? []).filter((a) => a.provider?.toUpperCase() === selected?.toUpperCase()),
    [accounts, selected]);

  const countFor = useCallback((provider: string) => {
    const rows = (accounts ?? []).filter((a) => a.provider?.toUpperCase() === provider.toUpperCase());
    return { total: rows.length, connected: rows.filter((a) => a.status === 'CONNECTED').length };
  }, [accounts]);

  return (
    <div>
      <h2 className="text-[18px] font-bold text-black mb-1">Security Monitoring</h2>
      <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">
        Enable XamSecure on a connected cloud account to get misconfiguration, vulnerability and
        runtime threat findings.
      </p>

      {loadError && (
        <p className="mb-4 text-[12px] text-red-600" role="alert">{loadError}</p>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
        {PROVIDERS.map((p) => {
          const { total, connected } = countFor(p.id);
          const active = selected === p.id;
          return (
            <button
              key={p.id}
              type="button"
              disabled={!p.supported}
              onClick={() => setSelected(active ? null : p.id)}
              title={p.supported ? undefined : 'Self-service setup is available for AWS today'}
              className={`flex flex-col items-center justify-center gap-2.5 p-6 rounded-xl border transition-all bg-white
                ${active ? 'border-[rgba(0,0,0,0.35)] shadow-sm' : 'border-[rgba(0,0,0,0.08)]'}
                ${p.supported ? 'hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm cursor-pointer'
                              : 'opacity-45 cursor-not-allowed'}`}
            >
              {p.iconImg
                ? <img src={p.iconImg} alt={p.name} className="w-8 h-8 object-contain" />
                : <Icon icon={p.icon!} width={32} />}
              <span className="text-[12px] font-medium text-[rgba(0,0,0,0.75)]">{p.name}</span>
              {/* Four distinct states. "Failed" previously rendered as an em-dash next to an
                  empty-state message, which read as "you have no accounts" — the opposite of
                  what had happened. */}
              <span className="text-[10px] text-[rgba(0,0,0,0.4)]">
                {!p.supported ? 'Coming soon'
                  : loadError ? 'Unavailable'
                  : accounts === null ? 'Loading…'
                  : total === 0 ? 'No accounts'
                  : `${connected}/${total} protected`}
              </span>
            </button>
          );
        })}
      </div>

      {selected && (
        <div className="mt-5 space-y-2.5">
          {/* Only claim "none" once a load has actually succeeded. While loading or after a
              failure, say so instead — the two are not the same thing and only one of them is
              the customer's problem to fix. */}
          {accounts === null && !loadError && (
            <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-6 text-center">
              <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Loading accounts…</p>
            </div>
          )}
          {accounts !== null && forProvider.length === 0 && (
            <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-6 text-center">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
                No {selected} accounts connected to XamOps yet
              </p>
              <p className="text-[12px] text-[rgba(0,0,0,0.4)] mt-1">
                Add the account under Data Sources above, then enable security monitoring here.
              </p>
            </div>
          )}
          {loadError && (
            <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-6 text-center">
              <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
                Could not load your accounts
              </p>
              <button type="button" onClick={() => { setAccounts(null); void load(); }}
                      className="mt-2 px-3 py-1.5 rounded-md text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.03)]">
                Retry
              </button>
            </div>
          )}

          {/* Sensor deployment lives here too, not only in the XamSecure tab: this is where
              someone is already enabling security monitoring, and the runtime signal is a
              separate step from connecting the account. Shown once because the script is
              organization-wide, with nothing in it keyed to an account. */}
          {selected === 'AWS' && <SensorDeployPanel />}
          {/* Kubernetes and ECS belong here for the same reason the host script does: this is
              where someone is already enabling security monitoring, and connecting an account
              does not by itself protect a single workload. Both are collapsed by default so
              they do not push the account list off the page. */}
          {selected === 'AWS' && <KubernetesSensorPanel accounts={forProvider} />}
          {selected === 'AWS' && <EcsSensorPanel accounts={forProvider} />}

          {forProvider.map((a) => {
            const busy = busyId === a.cloudAccountId;
            const result = checks[a.cloudAccountId];
            const error = errors[a.cloudAccountId];
            const connected = a.status === 'CONNECTED';
            return (
              <div key={a.cloudAccountId}
                   className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
                <div className="flex items-center justify-between gap-3 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.85)]">{a.accountName}</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)] font-mono">{a.cloudAccountId}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`px-2.5 py-1 rounded-full text-[10px] font-bold ${
                      connected ? 'bg-[rgba(0,0,0,0.88)] text-white'
                      : a.status === 'PENDING' ? 'bg-[rgba(0,0,0,0.10)] text-[rgba(0,0,0,0.7)]'
                      : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)]'}`}>
                      {STATUS_LABEL[a.status] ?? a.status}
                    </span>
                    {a.selfServiceSupported && !connected && (
                      <>
                        <button type="button" onClick={() => connect(a)} disabled={busy}
                                className="px-3 py-1.5 rounded-md text-[11px] font-semibold bg-[rgba(0,0,0,0.88)] text-white hover:bg-black disabled:opacity-50 transition-colors">
                          {a.status === 'PENDING' ? 'Re-open stack' : 'Deploy stack'}
                        </button>
                        {/* Offered for NOT_CONNECTED too, not just PENDING. An account connected
                            outside XamOps starts as NOT_CONNECTED, and with no button here it
                            had no way to be re-checked — it simply read "Not protected" for
                            ever. */}
                        <button type="button" onClick={() => check(a)} disabled={busy}
                                className="px-3 py-1.5 rounded-md text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50 transition-colors">
                          {busy ? 'Checking…' : 'Check status'}
                        </button>
                      </>
                    )}
                    {a.selfServiceSupported && (connected || a.status === 'PENDING') && (
                      <button type="button" onClick={() => setConfirmId(a.cloudAccountId)}
                              disabled={busy}
                              className="px-3 py-1.5 rounded-md text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50 transition-colors">
                        Disconnect
                      </button>
                    )}
                  </div>
                </div>

                {/* Inline confirmation rather than a modal: the second step (deleting the stack)
                    has to be readable next to the account it applies to, and it is the step people
                    skip — leaving the IAM role and the stored credential behind in their account. */}
                {confirmId === a.cloudAccountId && (
                  <div className="mt-3 rounded-lg border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] p-3">
                    <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.8)]">
                      Stop security monitoring for {a.accountName}?
                    </p>
                    <p className="mt-1 text-[12px] text-[rgba(0,0,0,0.55)]">
                      New findings stop immediately. Existing findings are kept for your records.
                      Afterwards, delete the CloudFormation stack
                      {a.stackName && <> <span className="font-mono">{a.stackName}</span></>}
                      {a.stackRegion && <> in <span className="font-mono">{a.stackRegion}</span></>}
                      {' '}to remove the IAM role and stored credential from your AWS account.
                    </p>
                    <div className="mt-2.5 flex items-center gap-2">
                      <button type="button" disabled={busy}
                              onClick={async () => { await disconnect(a); setConfirmId(null); }}
                              className="px-3 py-1.5 rounded-md text-[11px] font-semibold bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 transition-colors">
                        {busy ? 'Disconnecting…' : 'Disconnect'}
                      </button>
                      <button type="button" onClick={() => setConfirmId(null)}
                              className="px-3 py-1.5 rounded-md text-[11px] font-semibold border border-[rgba(0,0,0,0.12)] hover:bg-white transition-colors">
                        Cancel
                      </button>
                    </div>
                  </div>
                )}

                {disconnects[a.cloudAccountId] && (
                  <p className="mt-2.5 text-[12px] text-[rgba(0,0,0,0.6)]">
                    {disconnects[a.cloudAccountId].message}
                  </p>
                )}

                {/* Opening a CloudFormation console in a new tab is a surprising thing to do
                    without warning, so say it before the click rather than after. */}
                {!connected && a.status !== 'PENDING' && a.selfServiceSupported && (
                  <p className="mt-2.5 text-[12px] text-[rgba(0,0,0,0.5)]">
                    Opens AWS CloudFormation in a new tab, pre-filled. Review and create the stack
                    in this account — nothing to copy or paste.
                  </p>
                )}
                {/* Entitlement withheld from this client in BillOps admin. Distinct copy from the
                    unsupported-provider case, so nobody waits for a button that will never come. */}
                {!connected && !a.onboardingAllowed && (
                  <p className="mt-2.5 text-[12px] text-[rgba(0,0,0,0.5)]">
                    Security monitoring is not enabled for your organization. Contact your XamOps
                    administrator to have it turned on.
                  </p>
                )}
                {a.status === 'PENDING' && (
                  <p className="mt-2.5 text-[12px] text-[rgba(0,0,0,0.5)]">
                    Stack <span className="font-mono">{a.stackName}</span>
                    {a.stackRegion && <> in <span className="font-mono">{a.stackRegion}</span></>}.
                    Findings appear once the first scan completes.
                  </p>
                )}
                {result && (
                  <p className={`mt-2.5 text-[12px] ${result.reportingAssets ? 'text-emerald-700' : 'text-[rgba(0,0,0,0.5)]'}`}>
                    {result.message}
                  </p>
                )}
                {error && <p className="mt-2.5 text-[12px] text-red-600" role="alert">{error}</p>}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default XamSecureSection;
