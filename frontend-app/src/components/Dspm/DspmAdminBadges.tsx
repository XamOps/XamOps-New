import React from 'react';
import { Icon } from '@iconify/react';
import type { DspmConnectionStatus, DspmExpiryState } from '../../types/dspm';

/**
 * Status and expiry badges for the DSPM admin table.
 *
 * Colour is used sparingly and only where it carries meaning an operator must not miss — an expired
 * credential, a failed connection. Everything settled reads in the neutral grey the rest of the console
 * uses, so the table does not look like a dashboard of alarms when nothing is wrong.
 */

const NEUTRAL = 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)]';

const CONNECTION_META: Record<DspmConnectionStatus, { label: string; icon: string; cls: string }> = {
  CONNECTED: {
    label: 'Connected', icon: 'mdi:check-circle-outline',
    cls: 'bg-emerald-50 border-emerald-200 text-emerald-700',
  },
  UNVERIFIED: {
    // Not an error — it is the state every new or just-edited mapping starts in, so it stays neutral.
    label: 'Not verified', icon: 'mdi:help-circle-outline', cls: NEUTRAL,
  },
  INVALID_KEY: {
    label: 'Invalid key', icon: 'mdi:key-alert-outline',
    cls: 'bg-rose-50 border-rose-200 text-rose-700',
  },
  FORBIDDEN: {
    label: 'Scope denied', icon: 'mdi:lock-alert-outline',
    cls: 'bg-rose-50 border-rose-200 text-rose-700',
  },
  UNREACHABLE: {
    // Amber, not red: the vendor being down says nothing about the credential and needs no action here.
    label: 'Unreachable', icon: 'mdi:cloud-off-outline',
    cls: 'bg-amber-50 border-amber-200 text-amber-700',
  },
  DECRYPT_FAILED: {
    // Our fault, not the vendor's. Distinguished so nobody rotates a key that was never the problem.
    label: 'Decrypt failed', icon: 'mdi:shield-off-outline',
    cls: 'bg-rose-50 border-rose-200 text-rose-700',
  },
};

export const ConnectionBadge: React.FC<{ status: DspmConnectionStatus }> = ({ status }) => {
  const meta = CONNECTION_META[status]
    ?? { label: status, icon: 'mdi:help-circle-outline', cls: NEUTRAL };
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[11px] font-semibold ${meta.cls}`}>
      <Icon icon={meta.icon} width={11} />
      {meta.label}
    </span>
  );
};

/**
 * Key expiry, as a countdown.
 *
 * The column that earns its place. Matters caps key lifetime at 180 days and there is one key per
 * customer, so expiries arrive continuously — at fifty customers roughly one every three days. This is
 * the only place anyone would notice one coming before the customer does.
 */
export const ExpiryBadge: React.FC<{ state: DspmExpiryState; days: number | null }> = ({ state, days }) => {
  if (state === 'NONE') {
    // No expiry recorded. Worth showing rather than leaving blank: it may mean the key genuinely has no
    // expiry, or that nobody entered one — either way an operator should notice the gap.
    return (
      <span className="inline-flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.35)]">
        <Icon icon="mdi:minus" width={11} />
        Not set
      </span>
    );
  }

  if (state === 'EXPIRED') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[11px] font-semibold bg-rose-50 border-rose-200 text-rose-700">
        <Icon icon="mdi:key-remove" width={11} />
        Expired
      </span>
    );
  }

  const cls = state === 'WARNING'
    ? 'bg-amber-50 border-amber-200 text-amber-700'
    : NEUTRAL;

  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[11px] font-semibold ${cls}`}>
      <Icon icon="mdi:clock-outline" width={11} />
      {days === 0 ? 'Today' : `${days}d`}
    </span>
  );
};

/**
 * The stored key, identified without being revealed.
 *
 * Shows the environment and last four characters only. Four characters of a 43-character secret narrows
 * nothing, and it is what lets an operator confirm they configured the key they meant to.
 */
export const KeyBadge: React.FC<{
  env: string | null;
  lastFour: string | null;
  keyId: string | null;
}> = ({ env, lastFour, keyId }) => {
  if (!lastFour) {
    return (
      <span className="inline-flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.35)]">
        <Icon icon="mdi:key-outline" width={11} />
        None stored
      </span>
    );
  }
  return (
    <span
      className="inline-flex items-center gap-1.5 font-mono text-[11px] text-[rgba(0,0,0,0.6)]"
      title={keyId ? `Key id ${keyId}` : undefined}
    >
      {env && (
        <span className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] not-italic">{env}</span>
      )}
      <span>····{lastFour}</span>
    </span>
  );
};

/** Add-on entitlement, as an explicit-state switch rather than a toggle. */
export const EntitlementSwitch: React.FC<{
  enabled: boolean;
  disabled?: boolean;
  onChange: (enabled: boolean) => void;
}> = ({ enabled, disabled, onChange }) => (
  <button
    type="button"
    role="switch"
    aria-checked={enabled}
    aria-label={enabled ? 'Withdraw DSPM add-on' : 'Grant DSPM add-on'}
    disabled={disabled}
    // Sends the intended state, not a flip. A double-click on a paid capability must not silently
    // reverse the decision.
    onClick={() => onChange(!enabled)}
    className={`relative inline-flex h-5 w-9 flex-shrink-0 items-center rounded-full border transition-colors
      disabled:opacity-40 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.3)]
      ${enabled
        ? 'bg-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.88)]'
        : 'bg-[rgba(0,0,0,0.08)] border-[rgba(0,0,0,0.12)]'}`}
  >
    <span
      className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform
        ${enabled ? 'translate-x-[18px]' : 'translate-x-[3px]'}`}
    />
  </button>
);
