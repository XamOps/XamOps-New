import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchEcsDeployment, fetchEcsLaunchUrl, type EcsDeployment, type EcsLaunchType }
  from '../../hooks/useUpwindOnboarding';
import type { OnboardingAccount } from '../../types/xamsecure';

/**
 * ECS sensor deployment.
 *
 * Upwind's ECS stack asks for ClusterName, VPCID, VPCCIDRBlock and Subnets on top of the sensor
 * credential, and three of those four are why the deployment fails: the template's own Rules reject
 * an empty ClusterName or VPCID, VPCCIDRBlock has no default and feeds the security-group ingress
 * rule, and Subnets must be private with NAT access or the cluster manager's ENI comes up with no
 * route out. Asking a customer to recall those in a console they visit once is the whole problem, so
 * this resolves them from the inventory instead.
 *
 * EC2 and Fargate are separate Upwind stacks, not one stack with a flag, and they deploy different
 * things: EC2 gets a privileged per-host sensor plus a scanner, Fargate gets the cluster manager
 * alone because Fargate does not permit a privileged host daemon. The launch type therefore changes
 * what runtime coverage the customer ends up with, which is worth stating rather than implying.
 *
 * What it does not do is claim which subnets are private. Route-table associations are not available
 * to us, so private-ness is unknowable here and is left as the one decision the customer makes.
 */

interface Props {
  accounts: OnboardingAccount[];
  /** Expanded on arrival when the topbar's Add menu targeted this integration. */
  defaultOpen?: boolean;
}

const EcsSensorPanel: React.FC<Props> = ({ accounts, defaultOpen }) => {
  const eligible = accounts.filter(
    (a) => a.status === 'CONNECTED' && a.provider?.toUpperCase() === 'AWS');

  const [open, setOpen] = useState(Boolean(defaultOpen));
  const [accountId, setAccountId] = useState(eligible[0]?.cloudAccountId ?? '');
  const [launchType, setLaunchType] = useState<EcsLaunchType>('EC2');
  const [data, setData] = useState<EcsDeployment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [vpcId, setVpcId] = useState('');
  const [cluster, setCluster] = useState('');
  const [chosenSubnets, setChosenSubnets] = useState<string[]>([]);
  const [launching, setLaunching] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setData(null);
    setVpcId('');
    setCluster('');
    setChosenSubnets([]);
    try {
      const d = await fetchEcsDeployment(accountId, launchType);
      setData(d);
      if (d.vpcs.length === 1) setVpcId(d.vpcs[0].vpcId);
    } catch (err) {
      setError(err instanceof Error ? err.message
        : 'Could not resolve the deployment parameters.');
    } finally {
      setLoading(false);
    }
  }, [accountId, launchType]);

  const launch = useCallback(async () => {
    if (!vpc) return;
    setLaunching(true);
    setError(null);
    try {
      const res = await fetchEcsLaunchUrl({
        cloudAccountId: accountId,
        launchType,
        clusterName: cluster,
        vpcId: vpc.vpcId,
        vpcCidr: vpc.cidrBlock ?? '',
        subnets: chosenSubnets,
        region: vpc.region ?? '',
      });
      // Opened straight away and never held in state: the query string carries a credential.
      window.open(res.launchUrl, '_blank', 'noopener,noreferrer');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not build the launch URL.');
    } finally {
      setLaunching(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, launchType, cluster, chosenSubnets, vpcId, data]);

  // Discovered on account (or launch-type) selection rather than behind a button, matching how
  // Upwind's console populates its cluster dropdown. Only runs while the panel is open, so a
  // collapsed panel costs nothing.
  useEffect(() => {
    if (!open || !accountId) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, accountId, launchType]);

  const copy = useCallback(async (label: string, value: string) => {
    await navigator.clipboard.writeText(value);
    setCopied(label);
    window.setTimeout(() => setCopied(null), 2000);
  }, []);

  if (eligible.length === 0) return null;
  const vpc = data?.vpcs.find((v) => v.vpcId === vpcId) ?? null;

  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-3 px-5 py-4 text-left hover:bg-gray-50"
      >
        <Icon icon="mdi:docker" width={18} className="shrink-0 text-gray-500" />
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-semibold text-gray-900">Protect an ECS cluster</span>
          <span className="block text-[11px] text-gray-500">
            Runtime protection for containers on ECS. EC2 and Fargate.
          </span>
        </span>
        <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={18}
              className="shrink-0 text-gray-400" />
      </button>

      {open && (
        <div className="space-y-4 border-t border-gray-100 px-5 py-4">
          <div>
            <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-500">
              Launch type
            </p>
            <div className="flex gap-1.5">
              {(['EC2', 'FARGATE'] as EcsLaunchType[]).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => { setLaunchType(t); setData(null); }}
                  aria-pressed={launchType === t}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                    launchType === t
                      ? 'border-gray-900 bg-gray-900 text-white'
                      : 'border-gray-200 text-gray-600 hover:bg-gray-50'}`}
                >
                  {t === 'EC2' ? 'EC2' : 'Fargate'}
                </button>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <label className="block min-w-[16rem] flex-1">
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
            <button
              type="button"
              onClick={load}
              disabled={loading}
              title="Re-read clusters, VPCs and subnets from the inventory"
              className="inline-flex items-center gap-1.5 rounded-lg border border-gray-200 px-3
                         py-1.5 text-xs font-semibold text-gray-700 hover:bg-gray-50
                         disabled:opacity-50"
            >
              <Icon icon="mdi:refresh" width={13} className={loading ? 'animate-spin' : ''} />
              {loading ? 'Looking…' : 'Refresh'}
            </button>
          </div>

          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-[11px] text-red-700">{error}</p>
          )}

          {data && (
            <>
              {data.clusters.length === 0 ? (
                <p className="rounded-lg bg-gray-50 px-3 py-2 text-[11px] text-gray-600">
                  No ECS clusters found in this account's inventory. If you have just created one,
                  it will appear after the next inventory refresh.
                </p>
              ) : (
                <Field label="ECS cluster — select the one to protect">
                  <div className="flex flex-wrap gap-1.5">
                    {data.clusters.map((c) => (
                      <button
                        key={c}
                        type="button"
                        onClick={() => setCluster(c)}
                        aria-pressed={cluster === c}
                        title="Select this cluster"
                        className={`rounded-md border px-2 py-1 font-mono text-[10px] transition-colors ${
                          cluster === c
                            ? 'border-gray-900 bg-gray-900 text-white'
                            : 'border-gray-200 text-gray-700 hover:border-gray-400'}`}
                      >
                        {c}
                      </button>
                    ))}
                  </div>
                </Field>
              )}

              {data.vpcs.length > 0 && (
                <Field label="VPC">
                  <select
                    value={vpcId}
                    onChange={(e) => setVpcId(e.target.value)}
                    className="w-full rounded-lg border border-gray-200 px-2.5 py-1.5 text-xs
                               focus:border-gray-400 focus:outline-none"
                  >
                    <option value="">Select the VPC your cluster runs in…</option>
                    {data.vpcs.map((v) => (
                      <option key={v.vpcId} value={v.vpcId}>
                        {v.vpcId}
                        {v.cidrBlock ? ` · ${v.cidrBlock}` : ''}
                        {v.region ? ` · ${v.region}` : ''}
                        {v.isDefault ? ' · default' : ''}
                      </option>
                    ))}
                  </select>
                </Field>
              )}

              {vpc && (
                <div className="space-y-2 rounded-lg bg-gray-50 p-3">
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">
                    Template parameters
                  </p>
                  <ParamRow label="VPCID" value={vpc.vpcId} onCopy={copy} copied={copied} />
                  <ParamRow label="VPCCIDRBlock" value={vpc.cidrBlock ?? ''} onCopy={copy}
                            copied={copied} missing="Not reported — read it from the VPC console." />
                  <ParamRow label="Region" value={data.parameters.Region} onCopy={copy}
                            copied={copied} />
                  {data.launchType === 'FARGATE' && (
                    <ParamRow label="ClusterManagerCPUArchitecture" value=""
                              onCopy={copy} copied={copied}
                              missing="X86_64 or ARM64 — must match your tasks." />
                  )}
                  <ParamRow label="UpwindClientId" value={data.parameters.UpwindClientId}
                            onCopy={copy} copied={copied} />
                  <ParamRow label="UpwindClientSecret" value={data.parameters.UpwindClientSecret}
                            onCopy={copy} copied={copied} secret />

                  <div className="pt-1">
                    <p className="mb-1 text-[10px] font-semibold text-gray-500">
                      Subnets in this VPC ({vpc.subnets.length})
                    </p>
                    {vpc.subnets.length === 0 ? (
                      <p className="text-[10px] text-gray-400">
                        None discovered. Read them from the VPC console.
                      </p>
                    ) : (
                      <>
                        <div className="flex flex-wrap gap-1">
                          {vpc.subnets.map((sn) => {
                            const on = chosenSubnets.includes(sn);
                            return (
                              <button
                                key={sn}
                                type="button"
                                onClick={() => setChosenSubnets((prev) => on
                                  ? prev.filter((x) => x !== sn) : [...prev, sn])}
                                aria-pressed={on}
                                className={`rounded border px-1.5 py-0.5 font-mono text-[9px]
                                            transition-colors ${
                                  on ? 'border-gray-900 bg-gray-900 text-white'
                                     : 'border-gray-200 bg-white text-gray-600 hover:border-gray-400'}`}
                              >
                                {sn}
                              </button>
                            );
                          })}
                        </div>
                        <p className="mt-1.5 text-[10px] leading-relaxed text-amber-700">
                          Select only PRIVATE subnets with NAT access. We cannot tell which of these
                          are private, so this is the one value to verify yourself.
                          {!vpc.accountHasNatGateway
                            && ' No NAT gateway was found anywhere in this account.'}
                        </p>
                      </>
                    )}
                  </div>
                </div>
              )}

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label="This stack deploys">
                  <ul className="space-y-0.5">
                    {data.components.map((c) => (
                      <li key={c} className="flex gap-1.5 text-[10px] text-gray-700">
                        <Icon icon="mdi:cube-outline" width={11} className="mt-0.5 shrink-0 text-gray-400" />
                        <span>{c}</span>
                      </li>
                    ))}
                  </ul>
                </Field>
                <Field label="Cluster-manager ingress from the VPC CIDR">
                  <div className="flex flex-wrap gap-1">
                    {data.securityGroupPorts.map((p) => (
                      <span key={p}
                            className="rounded border border-gray-200 bg-white px-1.5 py-0.5
                                       font-mono text-[10px] text-gray-700">tcp/{p}</span>
                    ))}
                  </div>
                </Field>
              </div>

              <ul className="space-y-1">
                {data.notes.map((n) => (
                  <li key={n} className="flex gap-1.5 text-[10px] leading-relaxed text-gray-500">
                    <Icon icon="mdi:information-outline" width={11} className="mt-0.5 shrink-0" />
                    <span>{n}</span>
                  </li>
                ))}
              </ul>

              {data.templateUrl ? (
                <div className="space-y-1.5">
                  <button
                    type="button"
                    onClick={launch}
                    disabled={launching || !cluster || !vpc || chosenSubnets.length === 0}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-gray-900 px-3 py-1.5
                               text-xs font-semibold text-white hover:bg-gray-800 disabled:opacity-50"
                  >
                    {launching ? <Icon icon="mdi:loading" width={13} className="animate-spin" />
                      : <Icon icon="mdi:rocket-launch-outline" width={13} />}
                    Deploy in AWS
                  </button>
                  <p className="text-[10px] text-gray-400">
                    {!cluster ? 'Select a cluster to continue.'
                      : !vpc ? 'Select the VPC your cluster runs in.'
                        : chosenSubnets.length === 0 ? 'Select at least one private subnet.'
                          : `Opens CloudFormation in ${vpc.region ?? 'your account'} with every `
                            + 'parameter pre-filled. Review it before creating the stack.'}
                  </p>
                </div>
              ) : (
                <p className="rounded-lg bg-blue-50 px-3 py-2 text-[10px] leading-relaxed text-blue-800">
                  Run Upwind's current ECS template with the values above. A one-click launch will
                  appear here once the template URL is configured.
                </p>
              )}

              <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-3 py-2
                            text-[10px] leading-relaxed text-amber-800">
                <Icon icon="mdi:alert-outline" width={12} className="mt-0.5 shrink-0" />
                <span>
                  UpwindClientSecret is a credential. Do not paste it into a ticket, a chat or a
                  shared document.
                </span>
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
};

const Field: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div>
    <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-500">{label}</p>
    {children}
  </div>
);

const ParamRow: React.FC<{
  label: string;
  value: string;
  onCopy: (label: string, value: string) => void;
  copied: string | null;
  secret?: boolean;
  missing?: string;
}> = ({ label, value, onCopy, copied, secret, missing }) => (
  <div className="flex items-center gap-2 border-b border-gray-200 py-1 last:border-0">
    <span className="w-40 shrink-0 font-mono text-[10px] text-gray-500">{label}</span>
    <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-gray-800">
      {value ? (secret ? '••••••••••••••••' : value)
        : <span className="font-sans text-gray-400">{missing ?? '—'}</span>}
    </span>
    {value && (
      <button
        type="button"
        onClick={() => onCopy(label, value)}
        className="shrink-0 rounded border border-gray-200 bg-white px-1.5 py-0.5 text-[9px]
                   font-medium text-gray-600 hover:border-gray-400"
      >
        {copied === label ? 'Copied' : 'Copy'}
      </button>
    )}
  </div>
);

export default EcsSensorPanel;
