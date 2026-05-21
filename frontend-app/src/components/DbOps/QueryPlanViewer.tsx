import React, { useState } from 'react';

interface PlanNode {
  'Node Type'?: string;
  'Actual Rows'?: number;
  'Plan Rows'?: number;
  'Actual Total Time'?: number;
  'Rows Removed by Filter'?: number;
  'Shared Hit Blocks'?: number;
  'Shared Read Blocks'?: number;
  Plans?: PlanNode[];
  [key: string]: unknown;
}

function PlanNodeRow({ node, depth, parentTime }: {
  node: PlanNode;
  depth: number;
  parentTime?: number;
}) {
  const [open, setOpen] = useState(depth < 2);
  const children   = node.Plans ?? [];
  const nodeType   = node['Node Type'] ?? 'Node';
  const actualTime = node['Actual Total Time'] ?? 0;
  const actualRows = node['Actual Rows'];
  const planRows   = node['Plan Rows'];
  const rowsRemoved = node['Rows Removed by Filter'];
  const sharedHit  = node['Shared Hit Blocks'] ?? 0;
  const sharedRead = node['Shared Read Blocks'] ?? 0;
  const cacheHitPct = (sharedHit + sharedRead) > 0
    ? Math.round(sharedHit / (sharedHit + sharedRead) * 100)
    : null;

  const timePct = parentTime && parentTime > 0 ? actualTime / parentTime : 0;
  const timeColor = timePct > 0.8 ? 'text-red-500 dark:text-red-400'
    : timePct > 0.5 ? 'text-amber-500 dark:text-amber-400'
    : 'text-gray-700 dark:text-gray-300';

  return (
    <div style={{ marginLeft: depth * 16 }}>
      <div
        className="flex items-start gap-2 py-1.5 px-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700/30 cursor-pointer group"
        onClick={() => children.length > 0 && setOpen(o => !o)}
      >
        {children.length > 0
          ? <i className={`fas fa-chevron-${open ? 'down' : 'right'} text-[9px] text-gray-400 mt-1 shrink-0`} />
          : <span className="w-3 shrink-0" />}
        <div className="min-w-0 flex-1">
          <span className="text-xs font-semibold text-indigo-700 dark:text-indigo-300">{nodeType}</span>
          <div className="flex flex-wrap gap-x-4 gap-y-0.5 mt-0.5">
            {actualTime > 0 && (
              <span className={`text-[11px] ${timeColor}`}>
                <i className="fas fa-clock text-[9px] mr-0.5" />{actualTime.toFixed(2)} ms
              </span>
            )}
            {actualRows != null && (
              <span className="text-[11px] text-gray-500 dark:text-gray-400">
                <i className="fas fa-table-rows text-[9px] mr-0.5" />
                {actualRows.toLocaleString()} rows
                {planRows != null && planRows !== actualRows && (
                  <span className="ml-1 text-amber-500">(est {planRows.toLocaleString()})</span>
                )}
              </span>
            )}
            {rowsRemoved != null && rowsRemoved > 0 && (
              <span className="text-[11px] text-orange-500 dark:text-orange-400">
                <i className="fas fa-filter text-[9px] mr-0.5" />{rowsRemoved.toLocaleString()} filtered
              </span>
            )}
            {cacheHitPct != null && (
              <span className={`text-[11px] ${cacheHitPct < 80 ? 'text-red-500' : 'text-gray-400'}`}>
                <i className="fas fa-database text-[9px] mr-0.5" />{cacheHitPct}% cache
              </span>
            )}
          </div>
        </div>
      </div>
      {open && children.map((child, i) => (
        <PlanNodeRow key={i} node={child} depth={depth + 1} parentTime={actualTime || parentTime} />
      ))}
    </div>
  );
}

export function QueryPlanViewer({ planJson, engine, onClose }: {
  planJson: string;
  engine: string;
  onClose: () => void;
}) {
  let parsed: PlanNode | null = null;
  let parseError = '';

  try {
    const raw = JSON.parse(planJson);
    parsed = Array.isArray(raw) ? (raw[0]?.Plan ?? raw[0]) : raw;
  } catch {
    parseError = 'Could not parse plan JSON';
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-4 bg-black/40 backdrop-blur-sm"
      onClick={e => e.target === e.currentTarget && onClose()}
    >
      <div className="w-full max-w-2xl max-h-[80vh] flex flex-col bg-white dark:bg-gray-900 rounded-2xl shadow-2xl border border-gray-200 dark:border-gray-700">
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100 dark:border-gray-700 shrink-0">
          <div>
            <p className="text-sm font-semibold text-gray-800 dark:text-gray-100">Query Execution Plan</p>
            <p className="text-[11px] text-gray-400 mt-0.5">{engine} · EXPLAIN (no execution)</p>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
          >
            <i className="fas fa-times text-sm" />
          </button>
        </div>
        <div className="overflow-y-auto flex-1 p-4">
          {parseError && <p className="text-sm text-red-500">{parseError}</p>}
          {parsed && !parseError && <PlanNodeRow node={parsed} depth={0} />}
          {!parsed && !parseError && <p className="text-sm text-gray-400">No plan data</p>}
        </div>
        <details className="border-t border-gray-100 dark:border-gray-700 shrink-0">
          <summary className="px-5 py-3 text-[11px] text-gray-400 cursor-pointer hover:text-gray-600 select-none">
            Show raw JSON
          </summary>
          <pre className="px-5 pb-4 text-[10px] text-gray-500 overflow-x-auto max-h-48 bg-gray-50 dark:bg-gray-800">
            {JSON.stringify(JSON.parse(planJson || '{}'), null, 2)}
          </pre>
        </details>
      </div>
    </div>
  );
}
