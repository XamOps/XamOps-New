/**
 * Account and user overlays for automation (docs/cloudk8s/ui-revamp-design.md §8.2, §12.2).
 *
 * One banner per overlay that applies, in the §8.2 table order, each with its exact copy. A read-only
 * connection or a missing permission adds "Enable automation", which opens the checklist of what is
 * still needed. A read-write, permitted user sees nothing. Recommendations stay visible in every
 * overlay; only changes are off.
 */
import { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { Link } from 'react-router-dom';
import type { K8sCapabilitiesValue } from '../../../hooks/useK8sCapabilities';
import { findCluster } from '../../../hooks/useK8sCapabilities';
import { EnableAutomationPanel } from './EnableAutomationPanel';

export type AccessOverlay =
  | 'no-account-access'
  | 'capabilities-error'
  | 'read-only'
  | 'no-permission'
  | 'impersonation'
  | 'not-verified'
  | 'demo';

/** §8.2 copy, verbatim. */
export const OVERLAY_TEXT: Record<AccessOverlay, string> = {
  'no-account-access': "You don't have access to this account.",
  'capabilities-error': "Couldn't check automation access.",
  'read-only': 'Read-only view. Recommendations and projected savings are shown; automation changes are off.',
  'no-permission': 'You can view automation but not change it.',
  impersonation:
    "You're signed in as another user. Automation is view-only in this session. Return to your own session to change automation.",
  'not-verified': "This connection isn't verified.",
  demo: "Demo workspace. Changes aren't saved.",
};

const OVERLAY_ICON: Record<AccessOverlay, string> = {
  'no-account-access': 'mdi:account-lock-outline',
  'capabilities-error': 'mdi:alert-octagon',
  'read-only': 'mdi:eye-outline',
  'no-permission': 'mdi:account-lock-outline',
  impersonation: 'mdi:account-switch-outline',
  'not-verified': 'mdi:link-variant-off',
  demo: 'mdi:information-outline',
};

/** Which overlays apply, in display order. Loading shows none. */
export function accessOverlays(
  caps: Pick<K8sCapabilitiesValue, 'status' | 'data' | 'noAccess' | 'demo'>,
  clusterName?: string | null,
): AccessOverlay[] {
  if (caps.status === 'loading') return [];
  if (caps.status === 'error') return caps.noAccess ? ['no-account-access'] : ['capabilities-error', ...(caps.demo ? ['demo' as const] : [])];
  const data = caps.data;
  if (!data) return caps.demo ? ['demo'] : [];

  const cluster = findCluster(data, clusterName);
  const connection = cluster?.connection ?? data.connection;
  const out: AccessOverlay[] = [];
  if (connection.accessLevel !== 'READ_WRITE') out.push('read-only');
  if (data.user.reasons.includes('NO_USER_PERMISSION')) {
    out.push(data.user.detail === 'IMPERSONATION' ? 'impersonation' : 'no-permission');
  }
  if ((connection.status ?? '').toUpperCase() !== 'CONNECTED') out.push('not-verified');
  if (caps.demo || data.user.reasons.includes('DEMO')) out.push('demo');
  return out;
}

/** Overlays that offer the "Enable automation" checklist (§8.2). */
const OFFERS_ENABLE: ReadonlySet<AccessOverlay> = new Set<AccessOverlay>(['read-only', 'no-permission']);

const actionClass =
  'inline-flex items-center gap-1 rounded-md border border-[rgba(0,0,0,0.2)] bg-white px-2.5 py-1 text-[12px] font-semibold '
  + 'text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

export interface AccessBannerProps {
  capabilities: Pick<K8sCapabilitiesValue, 'status' | 'data' | 'noAccess' | 'demo' | 'refetch'>;
  /** The cluster whose own connection applies, when capabilities lists it. */
  cluster?: string | null;
  className?: string;
}

export function AccessBanner({ capabilities, cluster, className = '' }: AccessBannerProps) {
  const overlays = accessOverlays(capabilities, cluster);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  if (overlays.length === 0) return null;

  const enableAt = overlays.find((o) => OFFERS_ENABLE.has(o));
  return (
    <div className={`space-y-2 ${className}`} data-testid="access-banner">
      {overlays.map((overlay) => {
        const isError = overlay === 'capabilities-error' || overlay === 'no-account-access';
        return (
          <div
            key={overlay}
            role={isError ? 'alert' : undefined}
            data-overlay={overlay}
            className={`flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border px-3.5 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)] ${
              isError ? 'border-[rgba(0,0,0,0.88)] bg-white' : 'border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)]'
            }`}
          >
            <Icon icon={OVERLAY_ICON[overlay]} width={16} aria-hidden="true" className="shrink-0 text-[rgba(0,0,0,0.6)]" />
            <span className={`min-w-0 flex-1 ${isError ? 'font-semibold' : ''}`}>{OVERLAY_TEXT[overlay]}</span>
            {overlay === 'capabilities-error' && (
              <button type="button" onClick={capabilities.refetch} className={actionClass}>
                <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
                Retry
              </button>
            )}
            {overlay === 'not-verified' && (
              <Link to="/account-manager" className={actionClass}>Open Account Manager</Link>
            )}
            {overlay === enableAt && capabilities.data && (
              <button type="button" aria-expanded={open} aria-controls={panelId} onClick={() => setOpen((v) => !v)}
                className={actionClass}>
                Enable automation
                <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={14} aria-hidden="true" />
              </button>
            )}
          </div>
        );
      })}
      {enableAt && capabilities.data && open && (
        <EnableAutomationPanel id={panelId} data={capabilities.data} cluster={cluster} demo={capabilities.demo} />
      )}
      {enableAt && capabilities.data && !open && <span id={panelId} hidden />}
    </div>
  );
}

export default AccessBanner;
