import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  PageResp, InventoryAsset, InventoryCategory, NetworkTopology,
} from '../../types/xamsecure';
import InventoryAssetDrawer from './InventoryAssetDrawer';

interface Props {
  data: PageResp<InventoryAsset> | null;
  categories: InventoryCategory[] | null;
  network: NetworkTopology | null;
  isLoading: boolean;
  onLoad: (page: number, category: string) => void;
}

/** Readable labels + icons for networking sub-categories in the network view. */
const SUBCAT_META: Record<string, { label: string; icon: string }> = {
  virtual_private_cloud: { label: 'VPC', icon: 'mdi:cloud-outline' },
  security_group: { label: 'Security Groups', icon: 'mdi:shield-outline' },
  route_table: { label: 'Route Tables', icon: 'mdi:routes' },
  dns_manager: { label: 'DNS', icon: 'mdi:dns-outline' },
  internet_gateway: { label: 'Internet Gateways', icon: 'mdi:transit-connection-variant' },
  load_balancer: { label: 'Load Balancers', icon: 'mdi:scale-balance' },
};
const subLabel = (s: string) => SUBCAT_META[s]?.label ?? s.replace(/_/g, ' ');
const subIcon = (s: string) => SUBCAT_META[s]?.icon ?? 'mdi:lan';

/** Icon + readable label per Upwind asset category. */
const CATEGORY_META: Record<string, { label: string; icon: string }> = {
  compute_platform: { label: 'Compute', icon: 'mdi:server' },
  networking: { label: 'Networking', icon: 'mdi:lan' },
  storage: { label: 'Storage', icon: 'mdi:database' },
  security: { label: 'Security', icon: 'mdi:shield-lock' },
  monitoring: { label: 'Monitoring', icon: 'mdi:chart-line' },
  messaging: { label: 'Messaging', icon: 'mdi:email-outline' },
  database: { label: 'Database', icon: 'mdi:database-outline' },
  identity: { label: 'Identity', icon: 'mdi:account-key' },
  ai: { label: 'AI', icon: 'mdi:brain' },
};

const catLabel = (c: string | null): string =>
  c ? (CATEGORY_META[c]?.label ?? c.replace(/_/g, ' ')) : 'Uncategorized';
const catIcon = (c: string | null): string =>
  (c && CATEGORY_META[c]?.icon) || 'mdi:cube-outline';

const InventoryTab: React.FC<Props> = ({ data, categories, network, isLoading, onLoad }) => {
  const [selected, setSelected] = useState<InventoryAsset | null>(null);
  const [activeCategory, setActiveCategory] = useState<string>(''); // '' = all
  const [view, setView] = useState<'catalog' | 'network'>('catalog');
  const rows = data?.content ?? [];
  const cats = categories ?? [];
  const total = cats.reduce((sum, c) => sum + c.count, 0);

  const pick = (category: string) => {
    setActiveCategory(category);
    onLoad(0, category);
  };

  return (
    <div className="space-y-4">
      {/* Sub-view toggle (mirrors Upwind Inventory's Catalog / Runtime map tabs) */}
      <div className="flex items-center gap-2">
        <ViewTab label="Catalog" active={view === 'catalog'} onClick={() => setView('catalog')} />
        <ViewTab label="Network map" active={view === 'network'} onClick={() => setView('network')} />
      </div>

      {view === 'network' ? (
        <NetworkView network={network} isLoading={isLoading} />
      ) : (
        <div className="flex gap-4">
      {/* Category rail (Upwind Catalog) */}
      <aside className="w-56 flex-shrink-0 bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden self-start">
        <div className="px-4 py-3 border-b border-gray-200">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Catalog</p>
        </div>
        <nav className="p-2 space-y-0.5 max-h-[70vh] overflow-auto">
          <RailItem label="All assets" icon="mdi:view-grid-outline" count={total}
                    active={activeCategory === ''} onClick={() => pick('')} />
          {cats.map((c) => (
            <RailItem key={c.category ?? 'uncategorized'}
                      label={catLabel(c.category)}
                      icon={catIcon(c.category)}
                      count={c.count}
                      active={activeCategory === (c.category ?? '')}
                      onClick={() => pick(c.category ?? '')} />
          ))}
          {cats.length === 0 && (
            <p className="px-3 py-4 text-[11px] text-gray-400">
              {isLoading ? 'Loading categories...' : 'No assets.'}
            </p>
          )}
        </nav>
      </aside>

      {/* Asset table */}
      <div className="flex-1 min-w-0 bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
          <p className="text-xs font-semibold text-gray-700">
            {activeCategory === '' ? 'All assets' : catLabel(activeCategory)}
          </p>
          {data && <span className="text-[10px] text-gray-400">{data.totalElements} assets</span>}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Name</th>
                <th className="px-5 py-2.5">Type</th>
                <th className="px-5 py-2.5">Sub-category</th>
                <th className="px-5 py-2.5">Provider</th>
                <th className="px-5 py-2.5">Region</th>
                <th className="px-5 py-2.5">Resource ID</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.upwindId}
                    className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                    onClick={() => setSelected(a)}>
                  <td className="px-5 py-3 font-medium text-gray-800 max-w-xs truncate">{a.name}</td>
                  <td className="px-5 py-3 text-gray-600">{a.label}</td>
                  <td className="px-5 py-3 text-gray-600">{a.assetSubCategory}</td>
                  <td className="px-5 py-3 text-gray-600">{a.cloudProvider}</td>
                  <td className="px-5 py-3 text-gray-600">{a.region}</td>
                  <td className="px-5 py-3 font-mono text-[10px] text-gray-500 max-w-xs truncate">{a.cloudResourceId}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                    {isLoading ? 'Loading inventory...' : 'No assets for the selected filter.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
            <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} assets</span>
            <div className="flex gap-2">
              <button disabled={data.number === 0} onClick={() => onLoad(data.number - 1, activeCategory)}
                      className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
              <button disabled={data.number + 1 >= data.totalPages} onClick={() => onLoad(data.number + 1, activeCategory)}
                      className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
            </div>
          </div>
        )}
      </div>
        </div>
      )}

      {selected && (
        <InventoryAssetDrawer asset={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

/** Static config-topology stand-in for Upwind's runtime map. Real counts, no fabricated traffic. */
const NetworkView: React.FC<{ network: NetworkTopology | null; isLoading: boolean }> = ({ network, isLoading }) => {
  const nodes = network?.nodes ?? [];
  const totalNet = nodes.reduce((s, n) => s + n.count, 0);

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-6">
      {/* Honest disclaimer — this is not the live runtime map */}
      <div className="flex items-start gap-2 mb-6 px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg">
        <Icon icon="mdi:information-outline" width={16} className="text-gray-400 mt-0.5 flex-shrink-0" />
        <p className="text-[11px] text-gray-500 leading-relaxed">
          Static configuration topology from the asset inventory. Live traffic flows (traffic by
          process/port, connections) come from Upwind sensor telemetry, which the API does not expose.
        </p>
      </div>

      {nodes.length === 0 ? (
        <p className="py-12 text-center text-xs text-gray-400">
          {isLoading ? 'Loading network topology...' : 'No networking assets for the selected account.'}
        </p>
      ) : (
        <div className="flex flex-col items-center">
          {/* Hub: cloud account */}
          <div className="flex flex-col items-center">
            <div className="w-20 h-20 rounded-2xl bg-gray-900 text-white flex flex-col items-center justify-center shadow-md">
              <Icon icon="mdi:cloud" width={26} />
              <span className="text-[9px] font-bold mt-0.5">CLOUD</span>
            </div>
            <p className="mt-2 text-[11px] font-semibold text-gray-700">
              {network?.accountId ?? `${network?.accounts ?? 0} account(s)`}
            </p>
            <p className="text-[10px] text-gray-400">{totalNet} networking assets</p>
          </div>

          {/* Connector */}
          <div className="w-px h-8 bg-gray-200" />

          {/* Spokes: networking asset types */}
          <div className="flex flex-wrap justify-center gap-3">
            {nodes.map((n) => (
              <div key={n.subCategory}
                   className="w-32 flex flex-col items-center gap-1.5 px-3 py-4 border border-gray-200 rounded-xl hover:border-gray-300 transition-colors">
                <Icon icon={subIcon(n.subCategory)} width={22} className="text-gray-600" />
                <span className="text-[11px] font-medium text-gray-700 text-center leading-tight">
                  {subLabel(n.subCategory)}
                </span>
                <span className="text-lg font-bold text-gray-900">{n.count}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

const ViewTab: React.FC<{ label: string; active: boolean; onClick: () => void }> = ({ label, active, onClick }) => (
  <button
    onClick={onClick}
    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
      active ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-100'
    }`}
  >
    {label}
  </button>
);

const RailItem: React.FC<{ label: string; icon: string; count: number; active: boolean; onClick: () => void }> =
  ({ label, icon, count, active, onClick }) => (
    <button
      onClick={onClick}
      className={`w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-xs transition-colors ${
        active ? 'bg-gray-900 text-white' : 'text-gray-600 hover:bg-gray-50'
      }`}
    >
      <span className="flex items-center gap-2 min-w-0">
        <Icon icon={icon} width={14} className="flex-shrink-0" />
        <span className="truncate">{label}</span>
      </span>
      <span className={`text-[10px] font-semibold ${active ? 'text-gray-300' : 'text-gray-400'}`}>{count}</span>
    </button>
  );

export default InventoryTab;
