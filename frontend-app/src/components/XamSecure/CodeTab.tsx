import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { CodeScan, CodeScansResponse, CodeScanFindings } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import CodeScanDrawer from './CodeScanDrawer';
import FilterChips from './FilterChips';

interface Props {
  data: CodeScansResponse | null;
  isLoading: boolean;
  loadFindings: (fingerprint: string) => Promise<CodeScanFindings | null>;
}

const fmt = (iso: string | null): string => (iso ? new Date(iso).toLocaleString() : '—');

const TYPE_META: Record<string, { label: string; icon: string; color: string }> = {
  IMAGE_BUILD: { label: 'Image build', icon: 'logos:docker-icon', color: 'text-blue-600' },
  IAC_SCAN_BUILD: { label: 'IaC scan', icon: 'simple-icons:terraform', color: 'text-purple-600' },
};

const SOURCE_ICON: Record<string, string> = {
  GITHUB: 'mdi:github',
  GITLAB: 'mdi:gitlab',
  BITBUCKET: 'mdi:bitbucket',
};

/**
 * Code — Upwind's shift-left CI scan history.
 *
 * This answers a question the runtime vulnerability data cannot: not "what is deployed", but
 * "what is this commit about to add". That distinction is why the drawer leads with introduced
 * CVEs rather than a total.
 *
 * Scans without a fingerprint are shown but not clickable — the findings route is keyed on it, so
 * there is nothing to open. Roughly one scan in nine is in that state.
 */
const CodeTab: React.FC<Props> = ({ data, isLoading, loadFindings }) => {
  const [selected, setSelected] = useState<CodeScan | null>(null);
  const [branch, setBranch] = useState<string | null>(null);
  const all = data?.scans ?? [];

  const branches = useMemo(() => {
    const counts: Record<string, number> = {};
    all.forEach((s) => { if (s.branch) counts[s.branch] = (counts[s.branch] ?? 0) + 1; });
    // Main branches first, then busiest — a long tail of feature branches is not worth chips.
    return Object.entries(counts)
      .sort((a, b) => {
        const aMain = a[0] === 'main' || a[0] === 'master';
        const bMain = b[0] === 'main' || b[0] === 'master';
        if (aMain !== bMain) return aMain ? -1 : 1;
        return b[1] - a[1];
      })
      .slice(0, 6);
  }, [all]);

  const filtered = useMemo(
    () => (branch === null ? all : all.filter((s) => s.branch === branch)),
    [all, branch]);
  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 50);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading code scans...</div>;
  }

  // Withheld rather than empty: say so plainly instead of showing a bare "no data" table.
  if (data && !data.available) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-10 text-center">
        <Icon icon="mdi:source-branch" width={28} className="mx-auto text-gray-300" />
        <p className="mt-3 text-sm font-semibold text-gray-700">Code scans not shown</p>
        <p className="mt-1 mx-auto max-w-md text-xs text-gray-500 leading-relaxed">
          {data.unavailableReason
            ?? 'Code scan history is reported for the whole Upwind organization.'}
        </p>
      </div>
    );
  }

  const withheld = (data?.withheldNoMatch ?? 0) + (data?.withheldNoImage ?? 0)
    + (data?.withheldGenericImage ?? 0);

  return (
    <div className="space-y-4">
      {/* This view is partial by construction, so it says so rather than looking complete.
          Shift-left events carry no cloud account; a scan is shown only when its image is one we
          hold under an account you own. */}
      {withheld > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
          <Icon icon="mdi:information-outline" width={15}
                className="mt-0.5 flex-shrink-0 text-gray-400" />
          <p className="text-[11px] leading-relaxed text-gray-500">
            Showing {all.length.toLocaleString()} of {(all.length + withheld).toLocaleString()} CI
            scans in this Upwind organization. Scans carry no cloud account, so one is shown only
            when the image it built is running in an account you own.
            {' '}
            {data?.withheldNoImage ? (
              <>{data.withheldNoImage.toLocaleString()} carry no image at all (every IaC scan
                does not).{' '}</>
            ) : null}
            {data?.withheldNoMatch ? (
              <>{data.withheldNoMatch.toLocaleString()} built an image you do not run.{' '}</>
            ) : null}
            {data?.withheldGenericImage ? (
              <>{data.withheldGenericImage.toLocaleString()} built a public base image, where a
                name match would be coincidence rather than ownership.</>
            ) : null}
          </p>
        </div>
      )}

      {branches.length > 1 && (
        <FilterChips
          options={branches.map(([b, n]) => ({ value: b, label: b, count: n }))}
          active={branch}
          allCount={all.length}
          onChange={(v) => { setBranch(v); setPage(0); }}
        />
      )}

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            CI scans
          </p>
          <span className="text-[10px] text-gray-400">{total.toLocaleString()} scans</span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Repository</th>
                <th className="px-3 py-2.5">Branch</th>
                <th className="px-3 py-2.5">Type</th>
                <th className="px-3 py-2.5">Commit</th>
                <th className="px-3 py-2.5">Initiator</th>
                <th className="px-3 py-2.5">PR</th>
                <th className="px-3 py-2.5">Scanned</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s, i) => {
                const meta = TYPE_META[s.type ?? ''] ?? null;
                const openable = !!s.fingerprint;
                return (
                  <tr key={`${s.fingerprint ?? 'nofp'}-${s.commitSha ?? i}`}
                      className={`border-b border-gray-50 ${
                        openable ? 'hover:bg-gray-50 cursor-pointer' : 'opacity-70'}`}
                      title={openable ? undefined : 'This scan has no fingerprint, so Upwind reports no findings for it'}
                      onClick={openable ? () => setSelected(s) : undefined}>
                    <td className="px-5 py-3">
                      <span className="flex items-center gap-1.5 min-w-0">
                        <Icon icon={SOURCE_ICON[s.source ?? ''] ?? 'mdi:source-repository'}
                              width={13} className="flex-shrink-0 text-gray-500" />
                        <span className="font-medium text-gray-800 truncate">
                          {s.repository ?? '—'}
                        </span>
                      </span>
                      {/* The image, and the account it runs in, are the evidence for showing
                          this row at all — so they belong on the row, not hidden in a drawer. */}
                      {s.image && (
                        <span className="mt-0.5 flex items-center gap-1 text-[10px] text-gray-400">
                          <Icon icon="mdi:package-variant-closed" width={10}
                                className="flex-shrink-0" />
                          <span className="truncate max-w-[150px]" title={s.image}>{s.image}</span>
                          {s.cloudAccountIds && s.cloudAccountIds.length > 0 && (
                            <span className="font-mono" title="Account this image runs in">
                              · {s.cloudAccountIds.join(', ')}
                            </span>
                          )}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-3">
                      <span className="flex items-center gap-1 text-gray-600">
                        <Icon icon="mdi:source-branch" width={12} className="text-gray-400" />
                        <span className="truncate max-w-[180px]" title={s.branch ?? ''}>
                          {s.branch ?? '—'}
                        </span>
                      </span>
                    </td>
                    <td className="px-3 py-3">
                      {meta ? (
                        <span className="flex items-center gap-1.5">
                          <Icon icon={meta.icon} width={13} className="flex-shrink-0" />
                          <span className="text-gray-700">{meta.label}</span>
                        </span>
                      ) : <span className="text-gray-400">{s.type ?? '—'}</span>}
                    </td>
                    <td className="px-3 py-3 font-mono text-[11px] text-gray-500">
                      {s.commitSha ? s.commitSha.slice(0, 7) : '—'}
                    </td>
                    <td className="px-3 py-3 text-gray-600 truncate max-w-[140px]">
                      {s.initiator ?? '—'}
                    </td>
                    <td className="px-3 py-3">
                      {s.pullRequestUrl ? (
                        <a href={s.pullRequestUrl} target="_blank" rel="noreferrer noopener"
                           onClick={(e) => e.stopPropagation()}
                           className="inline-flex items-center gap-1 text-gray-600 hover:text-gray-900 underline decoration-dotted">
                          {s.pullRequestId ? `#${s.pullRequestId}` : 'Open'}
                          <Icon icon="mdi:arrow-top-right" width={11} />
                        </a>
                      ) : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="px-3 py-3 whitespace-nowrap text-gray-500">
                      {fmt(s.createTime)}
                    </td>
                  </tr>
                );
              })}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                    No CI scans reported by Upwind.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} onPage={setPage} unit="scans" />
      </div>

      {selected?.fingerprint && (
        <CodeScanDrawer scan={selected} load={loadFindings} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default CodeTab;
