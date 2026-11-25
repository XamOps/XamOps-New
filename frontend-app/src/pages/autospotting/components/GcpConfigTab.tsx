import React, { useState } from 'react';
import type { GroupDetail } from '../../../api/autospottingApi';
import { BTN_PRIMARY, CARD_TITLE, FIELD_LABEL, INPUT, MUTED } from './theme';

/** The GCP plan kind that saves rolling-update settings for a state, or null with the reason (design-gcp.md §3.5). */
export function policyPlanKind(state: string): { kind: string } | { reason: string } {
  switch (state) {
    case 'UNMANAGED':
    case 'REVERTED':
    case 'LEGACY_UNCONFIRMED':
      return { kind: 'MIG_POLICY_UPDATE' };
    case 'ACTIVE':
      return { kind: 'CONFIG_APPLY' };
    case 'PAUSED':
      return { reason: 'Resolve the drift first, then change the settings.' };
    case 'APPLYING':
    case 'REVERTING':
      return { reason: 'AutoSpotting is changing this group. Try again when it finishes.' };
    default:
      return { reason: 'Settings can be changed once AutoSpotting is not changing this group.' };
  }
}

type Fixed = { fixed?: number };
const fixedOf = (v: unknown): number | undefined => (v && typeof v === 'object' ? (v as Fixed).fixed : undefined);

/**
 * The rolling-update settings of a MIG, saved through a plan (replaces the legacy Config modal). It sends only
 * the fields that were edited; the plan shows the diff and nothing changes until it is confirmed.
 */
export const GcpConfigTab: React.FC<{
  detail: GroupDetail;
  current?: Record<string, unknown> | null;
  onReview: (kind: string, policyEdit: Record<string, unknown>) => void;
}> = ({ detail, current, onReview }) => {
  const live = (current?.updatePolicy ?? {}) as Record<string, unknown>;
  const [surge, setSurge] = useState('');
  const [unavailable, setUnavailable] = useState('');
  const [type, setType] = useState('');
  const decision = policyPlanKind(detail.group.state);

  const edit: Record<string, unknown> = {};
  const policy: Record<string, unknown> = {};
  if (surge !== '' && Number(surge) !== fixedOf(live.maxSurge)) policy.maxSurge = { fixed: Number(surge) };
  if (unavailable !== '' && Number(unavailable) !== fixedOf(live.maxUnavailable)) {
    policy.maxUnavailable = { fixed: Number(unavailable) };
  }
  if (type !== '' && type !== live.type) policy.type = type;
  if (Object.keys(policy).length) edit.updatePolicy = policy;
  const invalid = [surge, unavailable].some(v => v !== '' && (!Number.isInteger(Number(v)) || Number(v) < 0));

  return (
    <section aria-label="Rolling-update settings" className="mt-4 border-t border-[rgba(0,0,0,0.06)] pt-4 text-[13px]">
      <h3 className={`mb-3 ${CARD_TITLE}`}>Rolling-update settings</h3>
      <div className="grid grid-cols-3 gap-2">
        <label className="flex flex-col gap-1">
          <span className={FIELD_LABEL}>Max surge</span>
          <input
            type="number"
            min={0}
            value={surge}
            placeholder={String(fixedOf(live.maxSurge) ?? '')}
            onChange={e => setSurge(e.target.value)}
            className={`${INPUT} w-full`}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className={FIELD_LABEL}>Max unavailable</span>
          <input
            type="number"
            min={0}
            value={unavailable}
            placeholder={String(fixedOf(live.maxUnavailable) ?? '')}
            onChange={e => setUnavailable(e.target.value)}
            className={`${INPUT} w-full`}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className={FIELD_LABEL}>Update type</span>
          <select
            value={type}
            onChange={e => setType(e.target.value)}
            className={`${INPUT} w-full`}
          >
            <option value="">Unchanged</option>
            <option value="PROACTIVE">Proactive</option>
            <option value="OPPORTUNISTIC">Opportunistic</option>
          </select>
        </label>
      </div>
      {'reason' in decision ? (
        <p className={`mt-3 ${MUTED}`}>{decision.reason}</p>
      ) : (
        <button
          type="button"
          disabled={invalid || Object.keys(edit).length === 0}
          onClick={() => onReview(decision.kind, edit)}
          className={`mt-3 ${BTN_PRIMARY}`}
        >
          Review plan
        </button>
      )}
    </section>
  );
};

export default GcpConfigTab;
