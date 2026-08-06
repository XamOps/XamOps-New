import React from 'react';
import { Icon } from '@iconify/react';
import type { VulnGroupPage, VulnGroupDimension } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  data: VulnGroupPage | null | undefined;
  dimension: VulnGroupDimension;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const HEADINGS: Record<VulnGroupDimension, { primary: string; secondary: string }> = {
  image: { primary: 'Image', secondary: 'Tag' },
  package: { primary: 'Package', secondary: 'Version' },
  resource: { primary: 'Resource', secondary: 'Type' },
};

const ICONS: Record<VulnGroupDimension, string> = {
  image: 'mdi:layers-outline',
  package: 'mdi:package-variant-closed',
  resource: 'mdi:cube-outline',
};

/**
 * Findings grouped by image, package or resource.
 *
 * A CVE list answers "which vulnerability", which is rarely the unit of work — you patch an
 * image or bump a package. Upwind offers the same dimensions next to CVE for that reason, and
 * these rows carry the runtime rollup so a group can be judged without drilling in.
 */
const VulnGroupTable: React.FC<Props> = ({ data, dimension, isLoading, onPage }) => {
  const rows = data?.content ?? [];
  const head = HEADINGS[dimension];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">{head.primary}</th>
              <th className="px-3 py-2.5">{head.secondary}</th>
              <th className="px-3 py-2.5">Severity</th>
              <th className="px-3 py-2.5">Runtime</th>
              <th className="px-3 py-2.5">CVEs</th>
              <th className="px-3 py-2.5">Findings</th>
              <th className="px-3 py-2.5">Resources</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((g) => (
              <tr key={g.key} className="border-b border-gray-50 hover:bg-gray-50">
                <td className="px-5 py-3">
                  <span className="flex items-center gap-1.5 min-w-0">
                    <Icon icon={ICONS[dimension]} width={13} className="flex-shrink-0 text-gray-400" />
                    <span className="font-medium text-gray-800 truncate max-w-md" title={g.key}>
                      {g.key}
                    </span>
                  </span>
                </td>
                <td className="px-3 py-3 font-mono text-[11px] text-gray-500">
                  {g.secondary ?? <span className="text-gray-300">—</span>}
                </td>
                <td className="px-3 py-3"><SeverityChip severity={g.severity} /></td>
                <td className="px-3 py-3">
                  <span className="flex items-center gap-1.5">
                    {g.packageInUse && (
                      <span title="Package loaded by a running process">
                        <Icon icon="mdi:play-circle-outline" width={13} className="text-emerald-600" />
                      </span>
                    )}
                    {g.internetExposed && (
                      <span title="Takes inbound traffic">
                        <Icon icon="mdi:earth" width={13} className="text-orange-500" />
                      </span>
                    )}
                    {g.exploitable && (
                      <span title="Known exploit">
                        <Icon icon="mdi:bug" width={13} className="text-gray-700" />
                      </span>
                    )}
                    {!g.packageInUse && !g.internetExposed && !g.exploitable
                      && <span className="text-gray-300">—</span>}
                  </span>
                </td>
                <td className="px-3 py-3 font-semibold text-gray-700">{g.cveCount.toLocaleString()}</td>
                <td className="px-3 py-3 text-gray-600">{g.findingsCount.toLocaleString()}</td>
                <td className="px-3 py-3 text-gray-600">{g.resourceCount.toLocaleString()}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                  {isLoading
                    ? 'Loading...'
                    : dimension === 'image'
                      // Image context only exists on container findings; a host-only estate has none.
                      ? 'No container image context on these findings.'
                      : `No ${dimension} data for the selected account.`}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>
            Page {data.number + 1} of {data.totalPages} · {data.totalElements.toLocaleString()}{' '}
            {head.primary.toLowerCase()}s
          </span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">
              Prev
            </button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default VulnGroupTable;
