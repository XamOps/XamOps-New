import React, { useMemo } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { InventoryAsset } from '../../types/xamsecure';

interface Props {
  asset: InventoryAsset;
  onClose: () => void;
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
 * Read-only detail panel for an inventory asset. Renders the synced payload as a clean,
 * humanized key/value list (instead of raw JSON): a header with name + category, then the
 * asset's attributes. Portaled to body so it isn't clipped by the tab container.
 */
const InventoryAssetDrawer: React.FC<Props> = ({ asset, onClose }) => {
  const raw = useMemo<Record<string, unknown>>(() => {
    try { return asset.rawPayload ? JSON.parse(asset.rawPayload) : {}; }
    catch { return {}; }
  }, [asset.rawPayload]);

  // Build ordered key list: priority fields first, then the rest (excluding ones shown in header
  // and internal "never" epoch-zero risk markers).
  const entries = useMemo(() => {
    const skip = new Set(['id', 'name', 'asset_category', 'asset_sub_category', 'label']);
    const keys = Object.keys(raw).filter(
      (k) => !skip.has(k) && !isEpochZero(k, raw[k]) && renderVal(raw[k]) !== '—'
    );
    const ordered = [
      ...PRIORITY.filter((k) => keys.includes(k)),
      ...keys.filter((k) => !PRIORITY.includes(k)).sort(),
    ];
    return ordered.map((k) => [k, raw[k]] as const);
  }, [raw]);

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="relative w-full max-w-2xl h-full bg-white shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 mb-1.5">
              {asset.assetCategory && (
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-900 text-white uppercase tracking-wide">
                  {asset.assetCategory.replace(/_/g, ' ')}
                </span>
              )}
              {asset.assetSubCategory && (
                <span className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-gray-100 text-gray-600">
                  {asset.assetSubCategory.replace(/_/g, ' ')}
                </span>
              )}
              {asset.label && (
                <span className="text-[10px] font-mono text-gray-400">{asset.label}</span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug break-words">
              {asset.name ?? asset.cloudResourceId ?? 'Asset'}
            </h3>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0" aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden px-6 py-5">
          <div className="border border-gray-200 rounded-lg divide-y divide-gray-50">
            {entries.map(([k, v]) => (
              <div key={k} className="flex items-start gap-3 px-3 py-2">
                <span className="text-[10px] font-bold uppercase tracking-wide text-gray-400 w-44 flex-shrink-0 pt-0.5">
                  {humanize(k)}
                </span>
                <span className="text-xs text-gray-700 break-all min-w-0">{renderVal(v)}</span>
              </div>
            ))}
            {entries.length === 0 && (
              <div className="px-3 py-6 text-center text-xs text-gray-400">No additional attributes.</div>
            )}
          </div>
        </div>
      </div>
    </div>
  ), document.body);
};

export default InventoryAssetDrawer;
