import React, { useState } from 'react';
import type { PageResp, InventoryAsset } from '../../types/xamsecure';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<InventoryAsset> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const InventoryTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<InventoryAsset | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Name</th>
              <th className="px-5 py-2.5">Type</th>
              <th className="px-5 py-2.5">Category</th>
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
                <td className="px-5 py-3 text-gray-600">
                  {a.assetCategory}
                  <span className="block text-[10px] text-gray-400">{a.assetSubCategory}</span>
                </td>
                <td className="px-5 py-3 text-gray-600">{a.cloudProvider}</td>
                <td className="px-5 py-3 text-gray-600">{a.region}</td>
                <td className="px-5 py-3 font-mono text-[10px] text-gray-500 max-w-xs truncate">{a.cloudResourceId}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading inventory...' : 'No assets for the selected account.'}
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
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.name ?? 'Asset'} rawPayload={selected.rawPayload}
                       onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default InventoryTab;
