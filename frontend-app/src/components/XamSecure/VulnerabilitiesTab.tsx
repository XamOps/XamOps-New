import React, { useState } from 'react';
import type { PageResp, VulnerabilityFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<VulnerabilityFinding> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const VulnerabilitiesTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<VulnerabilityFinding | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">CVE</th>
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Vulnerability</th>
              <th className="px-5 py-2.5">CVSS</th>
              <th className="px-5 py-2.5">Exploitable</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">Last Scan</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((v) => (
              <tr key={v.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(v)}>
                <td className="px-5 py-3 font-mono text-gray-700 whitespace-nowrap">{v.cveId ?? '—'}</td>
                <td className="px-5 py-3"><SeverityChip severity={v.severity} /></td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{v.vulnerabilityName}</td>
                <td className="px-5 py-3 text-gray-600">{v.cvssScore ?? '—'}</td>
                <td className="px-5 py-3 text-gray-600">{v.exploitable === null ? '—' : v.exploitable ? 'Yes' : 'No'}</td>
                <td className="px-5 py-3 text-gray-600">
                  {v.resourceName}
                  <span className="block text-[10px] text-gray-400">{v.resourceType}</span>
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {v.lastScanTime ? new Date(v.lastScanTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading vulnerabilities...' : 'No vulnerability findings for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} findings</span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.vulnerabilityName ?? selected.cveId ?? 'Vulnerability'}
                       rawPayload={selected.rawPayload} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default VulnerabilitiesTab;
