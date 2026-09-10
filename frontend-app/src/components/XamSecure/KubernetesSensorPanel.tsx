import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchKubernetesCommand } from '../../hooks/useUpwindOnboarding';
import type { OnboardingAccount } from '../../types/xamsecure';

/**
 * Kubernetes sensor deployment.
 *
 * Per account and per cluster, unlike the host script: the command carries the cloud provider,
 * the account id and the cluster name, so it cannot be rendered once for the organization.
 *
 * This deliberately hands over the SURVEY command rather than an install command. The survey
 * inspects the cluster — proxy and metadata-service restrictions, core count — and then prints an
 * install command with resource limits and a replica count matched to that cluster. Those numbers
 * cannot be computed from outside the cluster, and guessing them is how a sensor daemonset ends up
 * OOM-killed or under-replicated.
 *
 * The command is fetched on submit and never on mount: it embeds a shared credential, so it does
 * not go on screen or into state before someone has asked for it.
 */

interface Props {
  /** The caller's own accounts. Only connected ones can host a sensor. */
  accounts: OnboardingAccount[];
  /** Expanded on arrival when the topbar's Add menu targeted this integration. */
  defaultOpen?: boolean;
}

const KubernetesSensorPanel: React.FC<Props> = ({ accounts, defaultOpen }) => {
  // Only connected accounts: a sensor on an unconnected account has nowhere to report.
  const eligible = accounts.filter((a) => a.status === 'CONNECTED');

  const [accountId, setAccountId] = useState(eligible[0]?.cloudAccountId ?? '');
  const [cluster, setCluster] = useState('');
  const [command, setCommand] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [open, setOpen] = useState(Boolean(defaultOpen));

  const submit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setCommand(null);
    try {
      setCommand((await fetchKubernetesCommand(accountId, cluster.trim())).command);
    } catch (err) {
      setError(err instanceof Error ? err.message
        : 'Could not build the command. Contact your XamOps administrator.');
    } finally {
      setLoading(false);
    }
  }, [accountId, cluster]);

  const copy = useCallback(async () => {
    if (!command) return;
    await navigator.clipboard.writeText(command);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  }, [command]);

  if (eligible.length === 0) return null;

  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-3 px-5 py-4 text-left hover:bg-gray-50"
      >
        <Icon icon="mdi:kubernetes" width={18} className="shrink-0 text-gray-500" />
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-semibold text-gray-900">
            Protect a Kubernetes cluster
          </span>
          <span className="block text-[11px] text-gray-500">
            Runtime protection for workloads running in EKS, AKS or GKE.
          </span>
        </span>
        <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18}
              className="shrink-0 text-gray-400" />
      </button>

      {open && (
        <div className="border-t border-gray-100 px-5 py-4">
          <form onSubmit={submit} className="space-y-3">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-gray-500">
                  Account
                </span>
                <select
                  value={accountId}
                  onChange={(e) => setAccountId(e.target.value)}
                  className="w-full rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs
                             focus:border-gray-400 focus:outline-none"
                >
                  {eligible.map((a) => (
                    <option key={a.cloudAccountId} value={a.cloudAccountId}>
                      {a.accountName} · {a.cloudAccountId}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-gray-500">
                  Cluster name
                </span>
                <input
                  value={cluster}
                  onChange={(e) => setCluster(e.target.value)}
                  placeholder="my-production-cluster"
                  required
                  // Mirrors the server-side check. The value is interpolated into a shell command
                  // the customer then runs, so it is validated on both sides rather than trusted.
                  pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,99}"
                  title="Letters, digits, dots, dashes and underscores; must start with a letter or digit."
                  className="w-full rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs
                             focus:border-gray-400 focus:outline-none"
                />
              </label>
            </div>

            <button
              type="submit"
              disabled={loading || cluster.trim() === ''}
              className="inline-flex items-center gap-1.5 rounded-lg bg-gray-900 px-3 py-1.5
                         text-xs font-semibold text-white hover:bg-gray-800 disabled:opacity-50"
            >
              {loading ? <Icon icon="mdi:loading" width={13} className="animate-spin" />
                : <Icon icon="mdi:console" width={13} />}
              Get install command
            </button>
          </form>

          {error && (
            <p className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-[11px] text-red-700">{error}</p>
          )}

          {command && (
            <div className="mt-4 space-y-3">
              <ol className="space-y-1.5 text-[11px] text-gray-600">
                <li className="flex gap-2">
                  <span className="font-semibold text-gray-400">1.</span>
                  <span>
                    Run this against the cluster. It inspects size and networking, then prints the
                    install command tuned to your cluster.
                  </span>
                </li>
                <li className="flex gap-2">
                  <span className="font-semibold text-gray-400">2.</span>
                  <span>Run the install command it gives you.</span>
                </li>
                <li className="flex gap-2">
                  <span className="font-semibold text-gray-400">3.</span>
                  <span>
                    Coverage appears above once the sensor reports — no confirmation step needed here.
                  </span>
                </li>
              </ol>

              <div className="relative">
                <pre className="overflow-x-auto rounded-lg bg-gray-900 px-3 py-2.5 pr-20
                                text-[10px] leading-relaxed text-gray-100">{command}</pre>
                <button
                  type="button"
                  onClick={copy}
                  className="absolute right-2 top-2 rounded-md bg-white/10 px-2 py-1 text-[10px]
                             font-medium text-white hover:bg-white/20"
                >
                  {copied ? 'Copied' : 'Copy'}
                </button>
              </div>

              <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-3 py-2
                            text-[10px] leading-relaxed text-amber-800">
                <Icon icon="mdi:alert-outline" width={12} className="mt-0.5 shrink-0" />
                <span>
                  This command contains a credential. Treat it like a password: do not paste it into
                  a ticket, a chat or a shared document.
                </span>
              </p>

              <p className="text-[10px] leading-relaxed text-gray-400">
                Vulnerability scanning runs as short-lived Kubernetes Jobs, so Job execution must be
                permitted in the cluster.
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default KubernetesSensorPanel;
