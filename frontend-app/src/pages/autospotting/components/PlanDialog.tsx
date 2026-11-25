import React, { useEffect, useState } from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import AsDialog from './AsDialog';
import ProfilePicker, { blockedReason, isProfileBlocked } from './ProfilePicker';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';
import { usePlanFlow } from './usePlanFlow';
import { warningText } from './reasons';
import { MAINTENANCE_POLICY_FIELD, MAINTENANCE_POLICY_HELP, POLICY_UNCHANGED, diffFieldLabel, diffValue } from './diffText';

export const BURSTABLE_LABEL = 'Allow burstable alternatives (for example t3a for t3)';
export const BURSTABLE_HELP =
  'Burstable instances earn CPU credits while they are idle and spend them when they are busy. If a busy instance runs out of credits, it slows down, or in unlimited mode you are charged extra for the surplus. Burstable Spot pools are small, and different types can behave differently, so cost and speed are harder to predict. Choose this if your workload is light or comes in short bursts.';
export const BURSTABLE_ALLOW_AND_REPLAN = 'Allow and re-plan';

const KIND_TITLE: Record<string, string> = {
  ENABLE: 'Review plan',
  PROFILE_CHANGE: 'Change profile',
  ADOPT: 'Adopt',
  CONFIG_APPLY: 'Re-apply',
  ACCEPT_BASELINE: 'Accept as baseline',
  MIG_POLICY_UPDATE: 'Review settings',
};

export const FEE_NONE = 'No AutoSpotting fee applies to this group.';
export const FEE_FOLLOWS = 'The fee follows your AutoSpotting contract in force each month';

/** The fee-basis line (PO-01, PO-16). Null when the plan carries no fee basis at all. */
export function feeBasisText(
  fee: { contractId?: string | null; reason?: string; sharePct?: number; monthlyCap?: number | null } | null,
): string | null {
  if (!fee) return null;
  if (!fee.contractId || fee.reason === 'NO_CONTRACT' || fee.reason === 'NO_FEE_ACCOUNT') return FEE_NONE;
  const share = fee.sharePct !== undefined ? `${fee.sharePct}% share` : 'Contract share';
  const cap = fee.monthlyCap != null ? `, monthly cap USD ${fee.monthlyCap}` : '';
  return `${share}${cap}. ${FEE_FOLLOWS}.`;
}

function useCountdown(expiresAt: string | null | undefined): number | null {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!expiresAt) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [expiresAt]);
  if (!expiresAt) return null;
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 1000));
}

function riskLabel(profile: string | null, warnings: number, fullSpot: boolean): 'Low' | 'Medium' | 'High' {
  if (fullSpot) return 'High';
  if (warnings > 0 || (profile !== null && profile !== 'conservative')) return 'Medium';
  return 'Low';
}

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <section className="mb-4">
    <h3 className="mb-1 text-sm font-semibold text-black">{title}</h3>
    <div className="text-sm text-black">{children}</div>
  </section>
);

interface PlanDialogProps {
  accountId: number | null;
  row: GroupRow;
  kind: string;
  /** GCP only: the edited rolling-update fields of the plan being reviewed. */
  policyEdit?: Record<string, unknown>;
  adapter: ProviderAdapter;
  /** Config `dryRun`; a plan flagged dry run also shows the banner. */
  dryRun?: boolean;
  onClose: () => void;
  onConfirmed: (operationId: string) => void;
  onViewProgress?: () => void;
  retryDelayMs?: number;
}

export const PlanDialog: React.FC<PlanDialogProps> = ({
  accountId,
  row,
  kind,
  policyEdit,
  adapter,
  dryRun,
  onClose,
  onConfirmed,
  onViewProgress,
  retryDelayMs,
}) => {
  const usesProfile = kind === 'ENABLE' || kind === 'PROFILE_CHANGE';
  const flow = usePlanFlow({ accountId, row, kind, usesProfile, policyEdit, retryDelayMs });
  const { plan, body } = flow;
  const remaining = useCountdown(plan?.expiresAt);
  const expired = remaining !== null && remaining <= 0;
  const isDry = !!(dryRun || plan?.dryRun);

  const estimates = body?.estimates ?? [];
  const desired = (body?.current?.desired as number | undefined) ?? row.desired;

  // GR-09: when Conservative is blocked, nothing is preselected and Confirm waits for a pick.
  const conservativeBlocked = isProfileBlocked(estimates.find(e => e.profile.toLowerCase() === 'conservative'));
  const selectedProfile = flow.profile && conservativeBlocked && flow.profile === 'conservative' ? null : flow.profile;
  const selectedBlocked = selectedProfile
    ? isProfileBlocked(estimates.find(e => e.profile.toLowerCase() === selectedProfile))
    : false;

  // The plan's own blockers join the list's: a check the list defers (instance types) is only known after planning.
  const listBlockers = row.blockers.filter(b => b.code !== 'PROFILE_NO_SPOT');
  const planBlockers = (body?.eligibility.blockers ?? [])
    .filter(b => !listBlockers.some(l => l.code === b.code))
    .map(b => ({ id: `plan-${b.code}`, code: b.code, text: b.text }));
  const blockers = [...listBlockers, ...planBlockers];
  const warnings = flow.warningIds.map(id => ({
    id,
    text: warningText(
      id,
      body?.eligibility.warnings.find(w => w.id === id)?.text ?? row.warnings.find(w => w.id === id)?.text,
    ),
  }));
  const burstable = body?.options ?? null;
  const burstableOn = flow.allowBurstable ?? burstable?.allowBurstable ?? false;
  const burstableOffer =
    !burstableOn &&
    (burstable?.burstableAlternatives?.length ?? 0) > 0 &&
    blockers.some(b => b.code === 'NO_COMPATIBLE_TYPES');
  // The explanation is about the setting XamOps adds; a customer's own policy ("unchanged") has nothing to explain.
  const policyRow = body?.diff.some(d => d.field === MAINTENANCE_POLICY_FIELD && d.to !== POLICY_UNCHANGED) ?? false;
  const allAcked = warnings.every(w => flow.acked.includes(w.id));
  const fullSpotNeeded = !!plan?.requiresFullSpotOptin;

  const reasons: string[] = [];
  if (!plan) reasons.push('The plan is loading.');
  if (plan && plan.status !== 'OPEN') reasons.push('This plan is no longer open.');
  if (expired) reasons.push('This plan expired. Refresh the plan.');
  if (blockers.length > 0) reasons.push('This group has an eligibility blocker.');
  if (usesProfile && !selectedProfile) reasons.push('Choose a profile.');
  if (selectedBlocked) reasons.push('The chosen profile is blocked at this size.');
  if (!allAcked) reasons.push('Tick every acknowledgment.');
  if (fullSpotNeeded && !flow.fullSpot) reasons.push('Opt in to 100% Spot.');
  if (flow.confirming) reasons.push('Confirming.');
  const canConfirm = reasons.length === 0;
  const reasonId = 'as-confirm-reason';

  const onConfirm = async () => {
    const opId = await flow.confirm();
    if (opId) onConfirmed(opId);
  };

  const fee = feeBasisText(body?.feeBasis ?? null);
  const stale = flow.outcome?.kind === 'stale';
  const title = KIND_TITLE[kind] ?? 'Review plan';

  return (
    <AsDialog
      title={`${title}: ${row.name}`}
      summary={`${adapter.groupNoun} in ${row.location}. Review the changes, then confirm.`}
      onClose={onClose}
      busy={flow.confirming}
      footer={
        <>
          {!canConfirm && (
            <span id={reasonId} className="mr-auto text-xs text-gray-600">
              {reasons[0]}
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            disabled={flow.confirming}
            className="rounded border border-gray-400 px-3 py-1 text-sm text-black disabled:opacity-50"
          >
            Cancel
          </button>
          {expired || stale ? (
            <button
              type="button"
              onClick={() => void flow.refreshPlan()}
              disabled={flow.creating}
              className="rounded border border-black bg-black px-3 py-1 text-sm text-white disabled:opacity-50"
            >
              Refresh plan
            </button>
          ) : (
            <button
              type="button"
              onClick={() => void onConfirm()}
              disabled={!canConfirm}
              aria-describedby={!canConfirm ? reasonId : undefined}
              className="rounded border border-black bg-black px-3 py-1 text-sm text-white disabled:border-gray-300 disabled:bg-gray-200 disabled:text-gray-500"
            >
              {isDry ? 'Run dry run' : 'Confirm'}
            </button>
          )}
        </>
      }
    >
      {isDry && (
        <p className="mb-3 rounded border border-gray-400 bg-gray-100 px-3 py-1 text-sm text-gray-600">Dry run</p>
      )}

      {flow.creating && !plan && (
        <p role="status" className="text-sm text-gray-600">
          Building the plan
        </p>
      )}

      {flow.createError ? (
        <p role="alert" className="mb-3 text-sm font-medium text-black">
          {flow.createErrorText}
        </p>
      ) : null}

      {flow.outcome && (
        <div role="alert" className="mb-3 rounded border-2 border-black p-3 text-sm text-black">
          {flow.outcome.kind === 'stale' && <p className="font-bold">{flow.outcome.title}</p>}
          <p>{flow.outcome.message}</p>
          {flow.outcome.kind === 'busy' && onViewProgress && (
            <button type="button" onClick={onViewProgress} className="mt-2 rounded border border-black px-3 py-1">
              Open Progress
            </button>
          )}
        </div>
      )}

      {plan && body && (
        <>
          {remaining !== null && !expired && (
            <p className="mb-3 text-xs text-gray-600">{`Plan valid for ${Math.floor(remaining / 60)}:${String(
              remaining % 60,
            ).padStart(2, '0')}`}</p>
          )}

          <Section title="What changes">
            {body.diff.length === 0 ? (
              <p className="text-gray-600">No field changes.</p>
            ) : (
              <table className="w-full text-left">
                <caption className="sr-only">Current and target configuration</caption>
                <thead>
                  <tr className="border-b border-gray-300 text-gray-600">
                    <th scope="col" className="py-1 pr-2 font-medium">
                      Field
                    </th>
                    <th scope="col" className="py-1 pr-2 font-medium">
                      Current
                    </th>
                    <th scope="col" className="py-1 font-medium">
                      Target
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {body.diff.map(d => (
                    <tr key={d.field} className="border-b border-gray-100">
                      <td className="py-1 pr-2">{diffFieldLabel(d.field)}</td>
                      <td className="py-1 pr-2">{diffValue(d.field, d.from, 'from')}</td>
                      <td className="py-1">{diffValue(d.field, d.to, 'to')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {policyRow && <p className="mt-1 text-gray-600">{MAINTENANCE_POLICY_HELP}</p>}
            <adapter.PlanTargetFields plan={plan} body={body} />
          </Section>

          <Section title="Snapshot">
            {body.revertPath?.exact === false ? (
              <p className="font-bold">Exact restore is not possible</p>
            ) : (
              <p>Original configuration will be saved.</p>
            )}
          </Section>

          {(blockers.length > 0 || warnings.length > 0) && (
            <Section title="Eligibility">
              {blockers.length > 0 && (
                <ul className="mb-2">
                  {blockers.map(b => (
                    <li key={b.id} className="font-medium">{`Blocked: ${b.text}`}</li>
                  ))}
                </ul>
              )}
              {burstableOffer && (
                <div className="mb-2">
                  <p>This group can convert if you allow burstable alternatives.</p>
                  <button
                    type="button"
                    onClick={() => void flow.selectAllowBurstable(true)}
                    disabled={flow.creating || flow.confirming}
                    className="mt-1 rounded border border-black px-3 py-1 text-sm text-black disabled:opacity-50"
                  >
                    {BURSTABLE_ALLOW_AND_REPLAN}
                  </button>
                </div>
              )}
              {warnings.map(w => (
                <label key={w.id} className="mb-1 flex items-start gap-2">
                  <input
                    type="checkbox"
                    checked={flow.acked.includes(w.id)}
                    onChange={() => flow.toggleAck(w.id)}
                    className="mt-1"
                  />
                  <span>{w.text}</span>
                </label>
              ))}
            </Section>
          )}

          {burstable?.burstableApplicable && (
            <Section title="Instance types">
              {!burstableOffer && (
                <label className="flex items-start gap-2">
                  <input
                    type="checkbox"
                    checked={burstableOn}
                    onChange={e => void flow.selectAllowBurstable(e.target.checked)}
                    disabled={flow.creating || flow.confirming}
                    className="mt-1"
                  />
                  <span>{BURSTABLE_LABEL}</span>
                </label>
              )}
              <p className="mt-1 text-gray-600">{BURSTABLE_HELP}</p>
              {(burstable.burstableAdded?.length ?? 0) > 0 && (
                <p className="mt-1">{`Burstable types added: ${(burstable.burstableAdded ?? []).join(', ')}`}</p>
              )}
            </Section>
          )}
          {body.rollout && (
            <Section title="Rollout">
              <p>
                {[
                  body.rollout.method,
                  body.rollout.instancesToReplace !== undefined
                    ? `${body.rollout.instancesToReplace} instances to replace`
                    : null,
                  body.rollout.surge !== undefined ? `surge ${body.rollout.surge}` : null,
                  body.rollout.durationRange ? `expected ${body.rollout.durationRange}` : null,
                ]
                  .filter(Boolean)
                  .join(', ')}
              </p>
              {body.rollout.headroom && <p className="text-gray-600">{body.rollout.headroom}</p>}
            </Section>
          )}

          {usesProfile && (
            <Section title="Profile">
              <ProfilePicker
                estimates={estimates}
                selected={selectedProfile}
                desired={desired}
                disabled={flow.creating || flow.confirming}
                onSelect={id => void flow.selectProfile(id)}
              />
              {conservativeBlocked && !selectedProfile && (
                <p className="mt-1 text-gray-600">{blockedReason(desired)}</p>
              )}
              {body.onDemandFloor && body.onDemandFloor.floorN !== undefined && (
                <p className="mt-2 text-gray-600">
                  {`Standard floor: ${String(body.onDemandFloor.floorN)} standard VMs at this size`}
                </p>
              )}
              {body.onDemandFloor && body.onDemandFloor.floorN === undefined && (
                <p className="mt-2 text-gray-600">
                  {`On-Demand floor: ${String(body.onDemandFloor.baseCapacity ?? '--')} base, ${String(
                    body.onDemandFloor.onDemandCount ?? '--',
                  )} On-Demand instances at this size`}
                </p>
              )}
            </Section>
          )}

          {estimates.length > 0 && (
            <Section title="Estimate">
              <ul>
                {estimates.map(e => (
                  <li key={e.profile} className="mb-1">
                    <span className="capitalize">{e.profile.toLowerCase()}</span>
                    {': '}
                    {isProfileBlocked(e) ? (
                      <span className="text-gray-600">Blocked at this size</span>
                    ) : e.monthlySavings === null ? (
                      NOT_PRICED
                    ) : (
                      <MoneyFigure amount={e.monthlySavings} label="Estimate" perMonth />
                    )}
                    {(e.priceSources ?? []).map((s, i) => (
                      <span key={i} className="block text-xs text-gray-600">
                        {[s.source, s.version, s.asOf ? `as of ${s.asOf}` : null].filter(Boolean).join(', ')}
                      </span>
                    ))}
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {fee && (
            <Section title="Fee basis">
              <p>{fee}</p>
            </Section>
          )}

          <Section title="Revert">
            <p>
              {body.revertPath?.exact === false
                ? 'Revert restores what it can. Exact restore is not possible.'
                : 'Revert restores the saved original configuration.'}
            </p>
          </Section>

          <Section title="Risk">
            <p>
              <strong>{riskLabel(selectedProfile, warnings.length, fullSpotNeeded && flow.fullSpot)}</strong>
            </p>
          </Section>

          {fullSpotNeeded && (
            <label className="mb-3 flex items-start gap-2 text-sm text-black">
              <input
                type="checkbox"
                checked={flow.fullSpot}
                onChange={e => flow.setFullSpot(e.target.checked)}
                className="mt-1"
              />
              <span>I want this group to run 100% Spot, with no On-Demand floor.</span>
            </label>
          )}

          <label className="block text-sm text-black">
            <span className="block text-gray-600">Reason (required for platform admins)</span>
            <input
              type="text"
              value={flow.reason}
              maxLength={500}
              onChange={e => flow.setReason(e.target.value)}
              className="mt-1 w-full rounded border border-gray-400 px-2 py-1"
            />
          </label>
        </>
      )}
    </AsDialog>
  );
};

export default PlanDialog;
