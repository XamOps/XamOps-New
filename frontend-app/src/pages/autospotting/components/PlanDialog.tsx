import React, { useEffect, useState } from 'react';
import type { GroupRow } from '../../../api/autospottingApi';
import type { ProviderAdapter } from '../adapters/types';
import AsDialog from './AsDialog';
import ProfilePicker, { blockedReason, isProfileBlocked } from './ProfilePicker';
import { MoneyFigure } from './MoneyLabel';
import { NOT_PRICED } from '../../../utils/formatMoney';
import { usePlanFlow } from './usePlanFlow';
import { warningText } from './reasons';
import { MAINTENANCE_POLICY_FIELD, MAINTENANCE_POLICY_HELP, POLICY_UNCHANGED } from './diffText';
import { Icon } from '@iconify/react';
import { BTN_PRIMARY, BTN_SECONDARY, CHECKBOX, FIELD_LABEL, INPUT, MUTED, Notice } from './theme';
import { BuildingPlan, DiffTable, Outcome } from './dialogParts';

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
  <section className="mb-5">
    <h3 className={`mb-2 ${FIELD_LABEL}`}>{title}</h3>
    <div className="text-[13px] text-[rgba(0,0,0,0.88)]">{children}</div>
  </section>
);
const RISK_CLASS: Record<string, string> = {
  Low: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  Medium: 'border-amber-200 bg-amber-50 text-amber-800',
  High: 'border-red-200 bg-red-50 text-red-700',
};

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
  const risk = riskLabel(selectedProfile, warnings.length, fullSpotNeeded && flow.fullSpot);

  return (
    <AsDialog
      title={`${title}: ${row.name}`}
      summary={`${adapter.groupNoun} in ${row.location}. Review the changes, then confirm.`}
      onClose={onClose}
      busy={flow.confirming}
      footer={
        <>
          {!canConfirm && (
            <span id={reasonId} className={`mr-auto text-[12px] ${MUTED}`}>
              {reasons[0]}
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            disabled={flow.confirming}
            className={BTN_SECONDARY}
          >
            Cancel
          </button>
          {expired || stale ? (
            <button
              type="button"
              onClick={() => void flow.refreshPlan()}
              disabled={flow.creating}
              className={BTN_PRIMARY}
            >
              Refresh plan
            </button>
          ) : (
            <button
              type="button"
              onClick={() => void onConfirm()}
              disabled={!canConfirm}
              aria-describedby={!canConfirm ? reasonId : undefined}
              className={BTN_PRIMARY}
            >
              {isDry ? 'Run dry run' : 'Confirm'}
            </button>
          )}
        </>
      }
    >
      {isDry && (
        <Notice tone="warning" icon="mdi:test-tube" className="mb-4">
          <p className="font-semibold">Dry run</p>
        </Notice>
      )}
      {flow.creating && !plan && <BuildingPlan />}
      {flow.createError ? (
        <Notice tone="danger" role="alert" className="mb-4">
          {flow.createErrorText}
        </Notice>
      ) : null}
      {flow.outcome && <Outcome outcome={flow.outcome} onViewProgress={onViewProgress} />}

      {plan && body && (
        <>
          {remaining !== null && !expired && (
            <p className={`mb-4 text-[12px] ${MUTED}`}>{`Plan valid for ${Math.floor(remaining / 60)}:${String(
              remaining % 60,
            ).padStart(2, '0')}`}</p>
          )}

          <Section title="What changes">
            {body.diff.length === 0 ? (
              <p className={MUTED}>No field changes.</p>
            ) : (
              <DiffTable caption="Current and target configuration" targetHeader="Target" diff={body.diff} />
            )}
            {policyRow && <p className={`mt-2 ${MUTED}`}>{MAINTENANCE_POLICY_HELP}</p>}
            <adapter.PlanTargetFields plan={plan} body={body} />
          </Section>

          <Section title="Snapshot">
            {body.revertPath?.exact === false ? (
              <Notice tone="warning">
                <p className="font-bold">Exact restore is not possible</p>
              </Notice>
            ) : (
              <p className="flex items-center gap-1.5">
                <Icon icon="mdi:content-save-check-outline" width={15} aria-hidden="true" className="text-emerald-700" />
                <span>Original configuration will be saved.</span>
              </p>
            )}
          </Section>

          {(blockers.length > 0 || warnings.length > 0) && (
            <Section title="Eligibility">
              {blockers.length > 0 && (
                <Notice tone="danger" icon="mdi:block-helper" className="mb-3">
                  <ul className="space-y-1">
                    {blockers.map(b => (
                      <li key={b.id} className="font-medium">{`Blocked: ${b.text}`}</li>
                    ))}
                  </ul>
                </Notice>
              )}
              {burstableOffer && (
                <Notice tone="info" icon="mdi:lightning-bolt-outline" className="mb-3">
                  <p>This group can convert if you allow burstable alternatives.</p>
                  <button
                    type="button"
                    onClick={() => void flow.selectAllowBurstable(true)}
                    disabled={flow.creating || flow.confirming}
                    className={`mt-2 ${BTN_PRIMARY}`}
                  >
                    {BURSTABLE_ALLOW_AND_REPLAN}
                  </button>
                </Notice>
              )}
              {warnings.length > 0 && (
                <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3">
                  {warnings.map(w => (
                    <label key={w.id} className="flex cursor-pointer items-start gap-2 text-amber-900">
                      <input
                        type="checkbox"
                        checked={flow.acked.includes(w.id)}
                        onChange={() => flow.toggleAck(w.id)}
                        className={CHECKBOX}
                      />
                      <span>{w.text}</span>
                    </label>
                  ))}
                </div>
              )}
            </Section>
          )}

          {burstable?.burstableApplicable && (
            <Section title="Instance types">
              <div className="rounded-xl border border-[rgba(0,0,0,0.1)] p-3">
                {!burstableOffer && (
                  <label className="flex cursor-pointer items-start gap-2 font-medium">
                    <input
                      type="checkbox"
                      checked={burstableOn}
                      onChange={e => void flow.selectAllowBurstable(e.target.checked)}
                      disabled={flow.creating || flow.confirming}
                      className={CHECKBOX}
                    />
                    <span>{BURSTABLE_LABEL}</span>
                  </label>
                )}
                <p className={`mt-1.5 text-[12px] leading-relaxed ${MUTED} ${!burstableOffer ? 'pl-6' : ''}`}>{BURSTABLE_HELP}</p>
                {(burstable.burstableAdded?.length ?? 0) > 0 && (
                  <p className={`mt-2 font-medium text-indigo-700 ${!burstableOffer ? 'pl-6' : ''}`}>
                    {`Burstable types added: ${(burstable.burstableAdded ?? []).join(', ')}`}
                  </p>
                )}
              </div>
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
              {body.rollout.headroom && <p className={`mt-1 ${MUTED}`}>{body.rollout.headroom}</p>}
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
                <p className={`mt-2 ${MUTED}`}>{blockedReason(desired)}</p>
              )}
              {body.onDemandFloor && body.onDemandFloor.floorN !== undefined && (
                <p className={`mt-2 ${MUTED}`}>
                  {`Standard floor: ${String(body.onDemandFloor.floorN)} standard VMs at this size`}
                </p>
              )}
              {body.onDemandFloor && body.onDemandFloor.floorN === undefined && (
                <p className={`mt-2 ${MUTED}`}>
                  {`On-Demand floor: ${String(body.onDemandFloor.baseCapacity ?? '--')} base, ${String(
                    body.onDemandFloor.onDemandCount ?? '--',
                  )} On-Demand instances at this size`}
                </p>
              )}
            </Section>
          )}

          {estimates.length > 0 && (
            <Section title="Estimate">
              <ul className="space-y-1.5">
                {estimates.map(e => (
                  <li key={e.profile}>
                    <span className="capitalize">{e.profile.toLowerCase()}</span>
                    {': '}
                    {isProfileBlocked(e) ? (
                      <span className={MUTED}>Blocked at this size</span>
                    ) : e.monthlySavings === null ? (
                      NOT_PRICED
                    ) : (
                      <MoneyFigure amount={e.monthlySavings} label="Estimate" perMonth />
                    )}
                    {(e.priceSources ?? []).map((s, i) => (
                      <span key={i} className={`block text-[11px] ${MUTED}`}>
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
              <strong className={`inline-block rounded-full border px-2.5 py-0.5 text-[12px] ${RISK_CLASS[risk]}`}>{risk}</strong>
            </p>
          </Section>

          {fullSpotNeeded && (
            <label className="mb-4 flex cursor-pointer items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-[13px] font-medium text-red-900">
              <input
                type="checkbox"
                checked={flow.fullSpot}
                onChange={e => flow.setFullSpot(e.target.checked)}
                className={CHECKBOX}
              />
              <span>I want this group to run 100% Spot, with no On-Demand floor.</span>
            </label>
          )}

          <label className="block">
            <span className={`block ${FIELD_LABEL}`}>Reason (required for platform admins)</span>
            <input
              type="text"
              value={flow.reason}
              maxLength={500}
              onChange={e => flow.setReason(e.target.value)}
              className={`mt-1.5 w-full ${INPUT}`}
            />
          </label>
        </>
      )}
    </AsDialog>
  );
};

export default PlanDialog;
