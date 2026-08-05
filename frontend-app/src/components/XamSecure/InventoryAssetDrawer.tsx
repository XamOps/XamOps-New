import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { InventoryAsset } from '../../types/xamsecure';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  asset: InventoryAsset;
  onClose: () => void;
  /** Jumps to the cross-domain view for this asset. */
  onOpenResource?: (resourceId: string) => void;
}

/** snake_case / camelCase → "Title Case". */
const humanize = (k: string): string =>
  k.replace(/^uw_/, '').replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (c) => c.toUpperCase());

const isEpochZero = (k: string, v: unknown): boolean =>
  k.startsWith('uw_risk') && (v === 0 || v === '0');

const renderVal = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (Array.isArray(v)) return v.length ? v.map(String).join(', ') : '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
};

// Fields surfaced first (in order) when present; everything else follows alphabetically.
const PRIORITY = [
  'cloud_resource_id', 'cloud_provider', 'cloud_account_id', 'region', 'availability_zone',
  'status', 'instance_type', 'platform', 'architecture', 'life_cycle',
  'private_ip', 'public_ip', 'private_dns_name', 'public_dns_name', 'uw_protected_by',
];

/**
 * Inventory asset panel.
 *
 * The Risk section is new-ish: it reads the catalog columns added in 08e86a88f — privilege,
 * data classification, EOL software, per-asset vulnerability and detection rollups. Before
 * those were persisted this panel could only re-print the raw payload, which is why Attributes
 * exists at all and why it stays: the payload carries provider-specific fields we deliberately
 * don't model, and dropping them would lose information.
 */
const InventoryAssetDrawer: React.FC<Props> = ({ asset, onClose, onOpenResource }) => {
  const raw = useMemo<Record<string, unknown>>(() => {
    try { return asset.rawPayload ? JSON.parse(asset.rawPayload) : {}; }
    catch { return {}; }
  }, [asset.rawPayload]);

  const entries = useMemo(() => {
    const skip = new Set(['id', 'name', 'asset_category', 'asset_sub_category', 'label']);
    const keys = Object.keys(raw).filter(
      (k) => !skip.has(k) && !isEpochZero(k, raw[k]) && renderVal(raw[k]) !== '—'
    );
    return [
      ...PRIORITY.filter((k) => keys.includes(k)),
      ...keys.filter((k) => !PRIORITY.includes(k)).sort(),
    ].map((k) => [k, raw[k]] as const);
  }, [raw]);

  const pii = asset.sensitivePiiAtRest || asset.sensitivePiiInTransit;
  const phi = asset.sensitivePhiAtRest || asset.sensitivePhiInTransit;
  const pci = asset.sensitivePciAtRest || asset.sensitivePciInTransit;
  const classified = pii || phi || pci;
  const hasRisk = asset.hasElevatedPrivilege != null || classified
    || asset.vulnTotalCount != null || asset.detectionTotalCount != null
    || asset.internetExposureType != null || asset.hasEolTechnology != null;

  const sections: PanelSection[] = [
    {
      id: 'overview', label: 'Overview', icon: 'mdi:information-outline', group: 'Asset',
      render: () => (
        <PanelBlock action={onOpenResource && asset.cloudResourceId ? (
          <button type="button" onClick={() => onOpenResource(asset.cloudResourceId!)}
                  className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2.5 py-1
                             text-[10px] font-bold text-gray-700 hover:border-gray-400
                             focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
            Open resource <Icon icon="mdi:arrow-top-right" width={11} />
          </button>
        ) : undefined}>
          <PanelRow label="Name" value={asset.name} />
          <PanelRow label="Type" value={asset.label} />
          <PanelRow label="Category" value={asset.assetCategory} />
          <PanelRow label="Sub-category" value={asset.assetSubCategory} />
          <PanelRow label="Cloud" value={asset.cloudProvider} />
          <PanelRow label="Account" value={asset.cloudAccountId} />
          <PanelRow label="Region" value={asset.region} />
          <PanelRow label="Status" value={asset.status} />
          {asset.cloudResourceId && (
            <PanelRow label="Resource id" value={asset.cloudResourceId} mono />
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'risk', label: 'Risk', icon: 'mdi:shield-alert-outline', group: 'Asset',
      available: hasRisk,
      unavailableReason:
        'Upwind reported no risk rollup for this asset. It may predate the catalog sync.',
      render: () => (
        <>
          <PanelBlock title="Exposure and privilege">
            <PanelRow label="Elevated privilege" value={<YesNo v={asset.hasElevatedPrivilege} />} />
            <PanelRow label="Internet exposure" value={asset.internetExposureType} />
            <PanelRow label="Last internet contact" value={
              asset.lastInternetCommunication
                ? new Date(asset.lastInternetCommunication).toLocaleString() : null} />
          </PanelBlock>
          <PanelBlock title="Data classification">
            {classified ? (
              <div className="flex flex-wrap gap-1.5">
                {pii && <Tag>PII</Tag>}
                {phi && <Tag>PHI</Tag>}
                {pci && <Tag>PCI</Tag>}
              </div>
            ) : (
              <p className="text-xs text-gray-500">
                No regulated data classified on this asset.
              </p>
            )}
          </PanelBlock>
          <PanelBlock title="Findings on this asset">
            <PanelRow label="Vulnerabilities" value={
              asset.vulnTotalCount == null ? null : (
                <span>
                  {asset.vulnTotalCount}
                  {(asset.vulnCriticalCount || asset.vulnHighCount) ? (
                    <span className="ml-2 text-[10px] text-gray-500">
                      {asset.vulnCriticalCount ?? 0} critical · {asset.vulnHighCount ?? 0} high
                    </span>
                  ) : null}
                </span>
              )} />
            <PanelRow label="Detections" value={asset.detectionTotalCount} />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'technologies', label: 'Technologies', icon: 'mdi:cog-outline', group: 'Asset',
      available: !!asset.technologies,
      unavailableReason: 'Upwind identified no technologies on this asset.',
      render: () => (
        <>
          {asset.hasEolTechnology && (
            <PanelBlock>
              <p className="flex items-start gap-1.5 rounded-lg border border-red-200 bg-red-50 p-2.5
                            text-[11px] text-red-700">
                <Icon icon="mdi:clock-alert-outline" width={13} className="mt-0.5 flex-shrink-0" />
                At least one technology here is past end-of-life or end-of-support. Unsupported
                software receives no security patches.
              </p>
            </PanelBlock>
          )}
          <PanelBlock>
            <div className="flex flex-wrap gap-1.5">
              {(asset.technologies ?? '').split(', ').filter(Boolean).map((t) => (
                <span key={t} className={`rounded-md border px-2 py-1 text-[11px] ${
                  t.includes('(EOL)')
                    ? 'border-red-200 bg-red-50 font-semibold text-red-700'
                    : 'border-gray-200 bg-gray-50 text-gray-700'}`}>
                  {t}
                </span>
              ))}
            </div>
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'attributes', label: 'Attributes', icon: 'mdi:code-braces', group: 'Asset',
      count: entries.length,
      available: entries.length > 0,
      unavailableReason: 'No additional attributes in the synced payload.',
      render: () => (
        <PanelBlock>
          {/* Provider-specific fields we deliberately don't model as columns. Kept so nothing
              Upwind sent is silently dropped. */}
          {entries.map(([k, v]) => (
            <PanelRow key={k} label={humanize(k)} value={renderVal(v)}
                      mono={k.includes('id') || k.includes('ip') || k.includes('dns')} />
          ))}
        </PanelBlock>
      ),
    },
  ];

  return (
    <DetailPanel
      title={asset.name ?? asset.cloudResourceId ?? 'Asset'}
      subtitle={
        <>
          {asset.label && (
            <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">
              {asset.label}
            </span>
          )}
          {asset.assetCategory && (
            <span className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-semibold text-gray-600">
              {asset.assetCategory}
            </span>
          )}
          {asset.hasElevatedPrivilege && (
            <span className="rounded-full bg-purple-100 px-2 py-0.5 text-[10px] font-bold text-purple-700">
              Elevated privilege
            </span>
          )}
          {classified && (
            <span className="rounded-full bg-purple-100 px-2 py-0.5 text-[10px] font-bold text-purple-700">
              Sensitive data
            </span>
          )}
          {asset.hasEolTechnology && (
            <span className="rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-bold text-red-700">
              End-of-life software
            </span>
          )}
        </>
      }
      sections={sections}
      onClose={onClose}
    />
  );
};

const Tag: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="rounded-full border border-purple-200 bg-purple-50 px-2.5 py-1
                   text-[11px] font-semibold text-purple-800">{children}</span>
);

const YesNo: React.FC<{ v: boolean | null | undefined }> = ({ v }) =>
  v == null ? <span className="text-gray-400">Not reported</span>
    : v ? <span className="font-semibold text-red-600">Yes</span>
      : <span className="text-gray-600">No</span>;

export default InventoryAssetDrawer;
