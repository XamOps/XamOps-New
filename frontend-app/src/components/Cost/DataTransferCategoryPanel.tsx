import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useMoney } from './costCurrency';
import { lockPageScroll } from './../../utils/scrollLock';
import type { Group } from './dataTransferModel';
import type { DataTransferCopy } from './DataTransferSection';
import {
  CATEGORIES,
  fallbackMeta,
  quantity,
  FlowTable,
  RouteTable,
  ResourceTable,
  UsageTable,
  type ViewKey,
} from './dataTransferView';

/**
 * One network category, in depth, in a panel over the page.
 *
 * <h2>Why a panel and not the inline accordion it replaced</h2>
 * The drill-down used to expand underneath the category row, which pushed every row below it down the
 * page and made two categories' detail impossible to compare without scrolling past the rows between
 * them. A reader asking "where is my cross-region money going, and how do I move it" had to expand a
 * row, read, collapse it, and expand the next. The panel keeps the overview list still and brings the
 * detail over the top of it, so the list stays where the reader left it and closing returns them to
 * exactly the row they opened. It mirrors {@code ResourceCostDrawer}, the sibling drawer in this
 * folder: portal to {@code document.body}, framer-motion slide from the right, Escape to close,
 * backdrop click to close, and the shared counted scroll lock.
 *
 * <h2>Why Routes first</h2>
 * The user's question is a region → region one — which lane costs the most and where could the
 * traffic go instead — so the panel opens on the Routes view, which draws each lane as a
 * "From → To" sentence. The other three views answer coarser questions off the same data and are a
 * segmented control away.
 */

interface DataTransferCategoryPanelProps {
  /** The category to describe, or null when the panel is closed. Only one is ever open. */
  group: Group | null;
  /** The overall network total, so the panel can state this category's share of it. */
  total: number;
  /** Whether the report carries the from/to location columns (drives the Routes-view note). */
  routeDataAvailable: boolean;
  /** Whether the report carries resource ids (drives the Resource / Resource+route empty states). */
  resourceDataAvailable: boolean;
  copy: DataTransferCopy;
  onClose: () => void;
}

/**
 * The view that answers something for this category, Routes-first.
 *
 * <p>Routes roll up from the uncapped charges grain, so they exist whenever any routed charge does —
 * which is why they are preferred. Falling back through flows to the raw usage types means a report
 * missing the optional route columns still opens onto a populated table rather than a notice.
 */
const defaultView = (g: Group): ViewKey => {
  if (g.routes.length > 0) return 'routes';
  if (g.flows.length > 0) return 'flows';
  return 'usage';
};

const DataTransferCategoryPanel: React.FC<DataTransferCategoryPanelProps> = ({
  group,
  total,
  routeDataAvailable,
  resourceDataAvailable,
  copy,
  onClose,
}) => {
  // Money inside the panel reads the section's CostCurrencyContext.Provider, which this panel renders
  // inside — so useMoney() here is correct (and rupee-billed GCP figures format correctly).
  const money = useMoney();
  const [view, setView] = useState<ViewKey>('routes');

  // Reset to the Routes-first default whenever a different category is opened: the reader's last view
  // choice was about the previous category, and opening another should land on its own best view.
  useEffect(() => {
    if (group) setView(defaultView(group));
  }, [group?.category]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!group) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [group, onClose]);

  // Freeze the page behind the panel through the shared counted lock, same as ResourceCostDrawer.
  useEffect(() => (group ? lockPageScroll() : undefined), [group]);

  const meta = group ? (CATEGORIES[group.category] ?? fallbackMeta) : fallbackMeta;
  const share = group && total > 0 ? (group.cost / total) * 100 : 0;
  const blurb = group ? (copy.categoryBlurbs?.[group.category] ?? meta.blurb) : '';

  return createPortal(
    <AnimatePresence>
      {group && (
        <React.Fragment key="data-transfer-category-panel">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            className="fixed inset-0 bg-black/30 z-50"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-modal="true"
            aria-labelledby="dt-panel-title"
            className="fixed right-0 top-0 h-full w-full sm:w-[48vw] sm:max-w-[720px] bg-white shadow-2xl z-50 flex flex-col"
          >
            {/* Header: what this category is, what it cost, how much of the bill it is. */}
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)]">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 flex items-start gap-2.5">
                  <span
                    className="flex-shrink-0 w-8 h-8 rounded-lg flex items-center justify-center mt-0.5"
                    style={{ backgroundColor: `${meta.color}1A` }}
                  >
                    <Icon icon={meta.icon} width={18} style={{ color: meta.color }} />
                  </span>
                  <div className="min-w-0">
                    <h3 id="dt-panel-title" className="text-[15px] font-bold text-black">
                      {group.category}
                    </h3>
                    <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5">
                      <span className="font-semibold text-black">{money(group.cost)}</span>
                      {group.gigabytes !== null && (
                        <>
                          <span className="text-[rgba(0,0,0,0.2)]">·</span>
                          <span>{quantity(group.gigabytes, 'GB')}</span>
                        </>
                      )}
                      <span className="text-[rgba(0,0,0,0.2)]">·</span>
                      <span>{share.toFixed(0)}% of network spend</span>
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close"
                  className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                >
                  <Icon icon="mdi:close" width={15} />
                </button>
              </div>

              {/* What this is and how to reduce it, near the top where the reduction guidance belongs. */}
              {blurb && (
                <p className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed mt-3">{blurb}</p>
              )}
            </div>

            {/* The views, Routes first. Plain buttons, a dialog's segmented control rather than
                page navigation — the same convention ResourceCostDrawer uses for its tabs. */}
            <nav
              role="tablist"
              aria-label="Break down by"
              className="flex items-center gap-1 px-4 py-2 border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)] flex-wrap"
            >
              {(
                [
                  ['routes', 'Routes', group.routes.length],
                  ['flows', 'Resource + route', group.flows.length],
                  ['resources', 'Resource', group.resources.length],
                  ['usage', 'Billed usage', group.charges.length],
                ] as [ViewKey, string, number][]
              ).map(([key, label, count]) => (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  aria-selected={view === key}
                  onClick={() => setView(key)}
                  className={`text-[11.5px] px-2.5 py-1 rounded-md border transition-colors ${
                    view === key
                      ? 'bg-white border-[rgba(0,0,0,0.15)] text-black font-semibold shadow-sm'
                      : 'bg-transparent border-transparent text-[rgba(0,0,0,0.5)] hover:text-black'
                  }`}
                >
                  {label}
                  {count > 0 && <span className="ml-1 text-[rgba(0,0,0,0.35)]">{count}</span>}
                </button>
              ))}
            </nav>

            <div className="flex-1 overflow-y-auto">
              {view === 'routes' && (
                <RouteTable
                  group={group}
                  color={meta.color}
                  routeColumns={routeDataAvailable}
                />
              )}
              {view === 'flows' && (
                <FlowTable
                  group={group}
                  resourceIds={resourceDataAvailable}
                  color={meta.color}
                  noResourceDetail={copy.noResourceDetail}
                />
              )}
              {view === 'resources' && (
                <ResourceTable
                  group={group}
                  resourceIds={resourceDataAvailable}
                  noResourceDetail={copy.noResourceDetail}
                />
              )}
              {view === 'usage' && <UsageTable group={group} />}
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

export default DataTransferCategoryPanel;
