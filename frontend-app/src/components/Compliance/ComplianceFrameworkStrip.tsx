import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { ComplianceFrameworkScore } from '../../types/compliance';
import FrameworkBadge from './FrameworkBadge';
import { CARD, COL_HEADER, EYEBROW, frameworkKey, frameworkName } from './complianceTokens';

interface Props {
  frameworks: ComplianceFrameworkScore[];
  /** Normalised framework key currently filtered on, or 'All'. */
  selected: string;
  onSelect: (key: string) => void;
}

type View = 'carousel' | 'grid';

/**
 * Per-framework rollup.
 *
 * Clicking a framework filters the controls list rather than opening a separate view:
 * the useful question is "what do I have to fix for PCI", and that is a filtered work
 * queue, not a second dashboard. A framework with nothing evaluated shows "—" instead of
 * 0%, for the same reason the overall score does.
 */
const ComplianceFrameworkStrip: React.FC<Props> = ({ frameworks, selected, onSelect }) => {
  const [view, setView] = useState<View>('carousel');
  const [edges, setEdges] = useState({ left: false, right: false });
  const scrollRef = useRef<HTMLDivElement>(null);

  const syncEdges = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    setEdges({
      left: el.scrollLeft > 4,
      right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4,
    });
  }, []);

  useEffect(() => {
    const id = requestAnimationFrame(syncEdges);
    return () => cancelAnimationFrame(id);
  }, [frameworks.length, view, syncEdges]);

  useEffect(() => {
    window.addEventListener('resize', syncEdges);
    return () => window.removeEventListener('resize', syncEdges);
  }, [syncEdges]);

  if (frameworks.length === 0) return null;

  return (
    <section className={CARD}>
      <header className="flex items-center justify-between gap-3 px-4 py-2.5 border-b border-[#e5e7eb]">
        <div className="flex items-center gap-2">
          <span className={EYEBROW}>Frameworks</span>
          <span className="px-1.5 py-0.5 rounded-md bg-[rgba(0,0,0,0.05)] text-[10.5px] font-semibold text-[rgba(0,0,0,0.5)] tabular-nums">
            {frameworks.length}
          </span>
          {selected !== 'All' && (
            <button
              type="button"
              onClick={() => onSelect('All')}
              className="inline-flex items-center gap-1 text-[11px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              <Icon icon="mdi:close-circle-outline" width={12} />
              Clear {frameworkName(selected)}
            </button>
          )}
        </div>

        <div className="flex items-center gap-2">
          {/* Segmented control: grey inset track, white raised thumb */}
          <div className="flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)]">
            {([
              { key: 'carousel' as View, icon: 'mdi:view-carousel-outline', label: 'Carousel' },
              { key: 'grid' as View, icon: 'mdi:view-grid-outline', label: 'Grid' },
            ]).map((opt) => (
              <button
                key={opt.key}
                type="button"
                onClick={() => setView(opt.key)}
                aria-pressed={view === opt.key}
                title={`${opt.label} view`}
                className={`w-7 h-7 flex items-center justify-center rounded-md transition-all ${
                  view === opt.key
                    ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
                    : 'text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.6)]'
                }`}
              >
                <Icon icon={opt.icon} width={15} />
              </button>
            ))}
          </div>

          {view === 'carousel' && (
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => scrollRef.current?.scrollBy({ left: -400, behavior: 'smooth' })}
                disabled={!edges.left}
                aria-label="Scroll frameworks left"
                className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] disabled:opacity-30 disabled:hover:border-[#e5e7eb] transition-colors"
              >
                <Icon icon="mdi:chevron-left" width={15} />
              </button>
              <button
                type="button"
                onClick={() => scrollRef.current?.scrollBy({ left: 400, behavior: 'smooth' })}
                disabled={!edges.right}
                aria-label="Scroll frameworks right"
                className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] disabled:opacity-30 disabled:hover:border-[#e5e7eb] transition-colors"
              >
                <Icon icon="mdi:chevron-right" width={15} />
              </button>
            </div>
          )}
        </div>
      </header>

      {view === 'grid' ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2 p-3">
          {frameworks.map((fw) => (
            <FrameworkCard
              key={fw.framework}
              fw={fw}
              selected={selected === frameworkKey(fw.framework)}
              onSelect={onSelect}
            />
          ))}
        </div>
      ) : (
        <div className="relative">
          <div
            ref={scrollRef}
            onScroll={syncEdges}
            className="flex gap-2 overflow-x-auto p-3 snap-x"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {frameworks.map((fw) => (
              <div key={fw.framework} className="flex-none w-[218px] snap-start">
                <FrameworkCard
                  fw={fw}
                  selected={selected === frameworkKey(fw.framework)}
                  onSelect={onSelect}
                />
              </div>
            ))}
          </div>
          <div
            className={`absolute left-0 top-0 bottom-0 w-8 pointer-events-none bg-gradient-to-r from-white to-transparent transition-opacity ${
              edges.left ? 'opacity-100' : 'opacity-0'
            }`}
          />
          <div
            className={`absolute right-0 top-0 bottom-0 w-8 pointer-events-none bg-gradient-to-l from-white to-transparent transition-opacity ${
              edges.right ? 'opacity-100' : 'opacity-0'
            }`}
          />
        </div>
      )}
    </section>
  );
};

const FrameworkCard: React.FC<{
  fw: ComplianceFrameworkScore;
  selected: boolean;
  onSelect: (key: string) => void;
}> = ({ fw, selected, onSelect }) => {
  const key = frameworkKey(fw.framework);
  const pct = fw.assessable && fw.score != null ? fw.score : 0;

  /**
   * What the sub-label says, in descending order of how much it can honestly claim.
   *
   * With a baseline on record the card can state scope — "43 of 62 clauses" — which is the
   * number that was missing. Without one it falls back to the count of clauses touched, and
   * deliberately does not imply a denominator it does not have.
   */
  const scope = fw.baselineControlCount != null && fw.baselineControlCount > 0
    ? `${fw.frameworkControlCount} of ${fw.baselineControlCount} clauses`
    : fw.frameworkControlCount > 0
      ? `${fw.frameworkControlCount} clauses mapped`
      : `${fw.totalControls} checks`;

  const titleParts = [
    frameworkName(fw.framework),
    `${fw.failedControls} failing of ${fw.settledControls} settled`,
    fw.coverage != null ? `${fw.coverage}% of mapped checks decided` : null,
    fw.clauseCoverage != null
      ? `${fw.clauseCoverage}% of the framework's clauses covered by this scan`
      : 'no clause baseline on record for this framework version',
    fw.riskWeightedScore != null ? `risk-weighted ${fw.riskWeightedScore}%` : null,
  ].filter(Boolean);

  return (
    <button
      type="button"
      onClick={() => onSelect(selected ? 'All' : key)}
      aria-pressed={selected}
      title={titleParts.join(' — ')}
      className={`w-full text-left p-3 rounded-lg border transition-colors ${
        selected
          ? 'border-[rgba(0,0,0,0.88)] bg-[#fafafa]'
          : 'border-[#e5e7eb] bg-white hover:border-[rgba(0,0,0,0.35)]'
      }`}
    >
      <div className="flex items-center gap-2.5">
        <FrameworkBadge framework={fw.framework} size={30} shape="rounded" />
        <div className="min-w-0 flex-1">
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate leading-tight">
            {frameworkName(fw.framework)}
          </p>
          <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] truncate mt-0.5">
            {scope}
          </p>
        </div>
        <span
          className={`text-[16px] font-semibold tabular-nums leading-none ${
            !fw.assessable
              ? 'text-[rgba(0,0,0,0.3)]'
              : pct >= 90
                ? 'text-emerald-600'
                : pct >= 75
                  ? 'text-amber-600'
                  : 'text-red-600'
          }`}
        >
          {fw.assessable && fw.score != null ? `${fw.score}%` : '—'}
        </span>
      </div>

      {/* Stacked bar: pass / fail / waived / not evaluated, in that order */}
      <div className="flex h-1.5 mt-2.5 rounded-full overflow-hidden bg-[rgba(0,0,0,0.05)]">
        <Segment value={fw.passedControls} total={fw.totalControls} className="bg-emerald-500" />
        <Segment value={fw.failedControls} total={fw.totalControls} className="bg-red-500" />
        <Segment value={fw.waivedControls} total={fw.totalControls} className="bg-sky-400" />
        <Segment
          value={fw.manualControls}
          total={fw.totalControls}
          className="bg-[rgba(0,0,0,0.18)]"
        />
      </div>

      <div className="flex items-center gap-2.5 mt-2 flex-wrap">
        <span className={COL_HEADER}>
          {fw.failedControls} failing
        </span>
        {fw.critical > 0 && (
          <span className="text-[10px] font-semibold uppercase tracking-widest text-red-600">
            {fw.critical} critical
          </span>
        )}
        {fw.waivedControls > 0 && (
          <span className="text-[10px] font-semibold uppercase tracking-widest text-sky-600">
            {fw.waivedControls} waived
          </span>
        )}
        {/*
          Shown only when the scan left part of this framework undecided. A silent card and a
          fully-decided card used to look identical, which is how a 62% over a third of the
          clauses read as confidently as one over all of them.
        */}
        {fw.coverage != null && fw.coverage < 100 && (
          <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.4)]">
            {fw.coverage}% decided
          </span>
        )}
      </div>
    </button>
  );
};

const Segment: React.FC<{ value: number; total: number; className: string }> = ({
  value, total, className,
}) => {
  if (total <= 0 || value <= 0) return null;
  return <span className={className} style={{ width: `${(value / total) * 100}%` }} />;
};

export default ComplianceFrameworkStrip;
