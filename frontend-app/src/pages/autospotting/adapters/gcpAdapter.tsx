import React from 'react';
import type { ProviderAdapter } from './types';

const NOT_READ = 'Shown when you review a plan';

const str = (v: unknown): string | null => {
  if (v === null || v === undefined || v === '') return null;
  if (Array.isArray(v)) return v.length ? v.map(x => String(x)).join(', ') : null;
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
};

/** The last path segment of a GCP self-link (the resource name). */
const shortName = (link: unknown): string | null => {
  const s = str(link);
  if (!s) return null;
  const i = s.lastIndexOf('/');
  return i >= 0 ? s.slice(i + 1) : s;
};

type Version = { name?: string; instanceTemplate?: string; targetSize?: { fixed?: number; percent?: number } };

const templatesText = (topLevel: unknown, versions: unknown): string | null => {
  const names = new Set<string>();
  const top = shortName(topLevel);
  if (top) names.add(top);
  if (Array.isArray(versions)) {
    (versions as Version[]).forEach(v => {
      const n = shortName(v.instanceTemplate);
      if (n && !n.startsWith('(')) names.add(n);
    });
  }
  return names.size ? Array.from(names).join(', ') : null;
};

const versionsText = (versions: unknown): string | null => {
  if (!Array.isArray(versions) || versions.length === 0) return null;
  return (versions as Version[])
    .map(v => {
      const size = v.targetSize?.fixed !== undefined ? `fixed ${v.targetSize.fixed}` : 'the remainder';
      return `${v.name ?? 'unnamed'} (${size})`;
    })
    .join(', ');
};

const scopeText = (locationType: unknown, location: unknown): string | null => {
  const t = str(locationType);
  if (!t) return null;
  const label = t === 'REGIONAL' ? 'Regional' : t === 'ZONAL' ? 'Zonal' : 'Unknown';
  const loc = str(location);
  return loc ? `${label} (${loc})` : label;
};

const distributionText = (zones: unknown, locationType: unknown): string | null => {
  if (str(locationType) === 'ZONAL') return 'Single zone';
  if (!Array.isArray(zones) || zones.length === 0) return null;
  return `${zones.length} zones: ${zones.map(z => String(z)).join(', ')}`;
};

const updatePolicyText = (p: unknown): string | null => {
  if (!p || typeof p !== 'object') return null;
  const o = p as Record<string, unknown>;
  const surge = (o.maxSurge as { fixed?: number } | undefined)?.fixed;
  const unavailable = (o.maxUnavailable as { fixed?: number } | undefined)?.fixed;
  const parts = [
    str(o.type),
    str(o.minimalAction) ? `minimal action ${String(o.minimalAction)}` : null,
    surge !== undefined ? `max surge ${surge}` : null,
    unavailable !== undefined ? `max unavailable ${unavailable}` : null,
  ].filter(Boolean);
  return parts.length ? parts.join(', ') : null;
};

const floorMechanismText = (mechanism: unknown): string | null => {
  const m = str(mechanism);
  if (!m) return null;
  return m === 'TWO_VERSION_MIG' ? 'Two template versions (standard floor plus Spot)' : m;
};

const Field: React.FC<{ label: string; value: string | null }> = ({ label, value }) => (
  <div className="flex justify-between gap-4 py-1.5 border-b border-gray-100 text-sm">
    <dt className="text-gray-500">{label}</dt>
    <dd className="text-black text-right">{value ?? NOT_READ}</dd>
  </div>
);

export const gcpAdapter: ProviderAdapter = {
  provider: 'GCP',
  icon: '/icons/gcp-compute-engine.png',
  groupNoun: 'Managed Instance Group',
  locationLabel: 'Location',
  columns: [{ id: 'instanceType', header: 'Machine type', render: row => row.instanceType ?? '--' }],

  ConfigFields: ({ current }) => {
    const c = current ?? {};
    return (
      <dl>
        <Field label="Instance templates" value={templatesText(c.instanceTemplate, c.versions)} />
        <Field label="Zonal or regional" value={scopeText(c.locationType, c.location)} />
        <Field label="Distribution shape" value={distributionText(c.distributionZones, c.locationType)} />
        <Field label="Update policy" value={updatePolicyText(c.updatePolicy)} />
        <Field label="Floor mechanism" value={floorMechanismText(c.floorMechanism) ?? (current ? 'None (not converted)' : null)} />
        <Field label="Preemptions in the last 7 days" value="Not tracked yet" />
      </dl>
    );
  },

  PlanTargetFields: ({ body }) => {
    const t = body.target ?? {};
    const floor = body.onDemandFloor ?? {};
    if (t.policyOnly) {
      const edit = (t.edit ?? {}) as Record<string, unknown>;
      const prior = (t.prior ?? {}) as Record<string, unknown>;
      return (
        <dl>
          <Field label="New rolling-update settings" value={updatePolicyText(edit.updatePolicy)} />
          <Field label="Current settings" value={updatePolicyText(prior.updatePolicy)} />
          <Field label="Template, size and instances" value="Not changed" />
        </dl>
      );
    }
    return (
      <dl>
        <Field label="Floor mechanism" value={floorMechanismText(t.floorMechanism)} />
        <Field label="Template versions" value={versionsText(t.versions)} />
        <Field label="Standard floor" value={floor.floorN === undefined ? null : `${String(floor.floorN)} standard VMs`} />
        <Field label="Rollout update policy" value={updatePolicyText(t.updatePolicy)} />
      </dl>
    );
  },

  permissionGuidance: code => {
    if (code === 'GCP_PERMISSION' || code === 'REVERT_NEEDS_PERMISSION') {
      return {
        label: 'Grant access',
        text: 'XamOps is missing a permission on this project. Re-run the XamOps service account script, then try again.',
        href: '/account-manager',
      };
    }
    return {
      label: 'Grant access',
      text: "XamOps can't read Compute Engine in this project. Check the service account connected for it.",
      href: '/account-manager',
    };
  },
};
