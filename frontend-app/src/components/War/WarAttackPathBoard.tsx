import React from 'react';
import { Icon } from '@iconify/react';
import type { WarAttackPathSummary } from '../../types/war';
import { ATTACK_PATH_ICONS, CARD, EYEBROW, PATH_STATE_TOKENS, severityToken } from './warTokens';

interface Props {
  paths: WarAttackPathSummary[];
  activePath: string;
  onSelect: (path: string) => void;
}

/**
 * Controls on a path that are not confirmed safe.
 *
 * Unverified counts as open. The card previously showed only failed+warned, so a path
 * could read "0 of 8 controls open" and then filter to eight unverified findings — the
 * card contradicting the list it drives.
 */
const openCountOf = (path: WarAttackPathSummary): number =>
  path.failed + path.warned + path.unverified;

/**
 * Attack paths, ordered by how exposed each one is.
 *
 * This is the page's primary axis rather than the control family, because the question
 * being answered is "how would someone get in and start mining?" — and the answer to
 * that spans IAM, network and detection controls at once. Selecting a card filters the
 * findings table and scrolls to it.
 */
const WarAttackPathBoard: React.FC<Props> = ({ paths, activePath, onSelect }) => {
  if (paths.length === 0) return null;

  return (
    <div className={`${CARD} p-4`}>
      <div className="flex items-center gap-3 mb-3">
        <span className={`${EYEBROW} whitespace-nowrap`}>Attack paths</span>
        <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]" />
        <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap hidden sm:block">
          How a failed control gets monetised
        </span>
        {activePath !== 'All' && (
          <button
            onClick={() => onSelect('All')}
            className="text-[11.5px] font-medium text-[rgba(0,0,0,0.5)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors whitespace-nowrap"
          >
            Clear
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-2.5">
        {paths.map((path) => {
          const stateToken = PATH_STATE_TOKENS[path.state] ?? PATH_STATE_TOKENS.UNVERIFIED;
          const isActive = activePath === path.path;
          const openCount = openCountOf(path);
          const confirmedOpen = path.failed + path.warned;
          const coverage = path.total > 0
            ? Math.round(((path.total - openCount) / path.total) * 100)
            : 0;

          return (
            <button
              key={path.path}
              onClick={() => onSelect(isActive ? 'All' : path.path)}
              aria-pressed={isActive}
              className={`text-left rounded-lg border p-3 transition-all ${
                isActive
                  ? 'border-[rgba(0,0,0,0.88)] bg-[rgba(0,0,0,0.02)]'
                  : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm'
              }`}
            >
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-1.5 min-w-0">
                  <Icon
                    icon={ATTACK_PATH_ICONS[path.path] ?? 'mdi:shield-outline'}
                    width={14}
                    className="text-[rgba(0,0,0,0.5)] shrink-0"
                  />
                  <span className="text-[12.5px] font-semibold text-[rgba(0,0,0,0.88)] truncate">
                    {path.label}
                  </span>
                </div>
                <span
                  className={`px-2 py-0.5 rounded-full text-[9px] font-semibold uppercase tracking-wider shrink-0 ${stateToken.classes}`}
                >
                  {stateToken.label}
                </span>
              </div>

              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1.5 leading-snug line-clamp-2">
                {path.description}
              </p>

              <div className="mt-2.5 flex items-baseline gap-1.5">
                <span className="text-[20px] font-bold text-black leading-none tabular-nums">
                  {openCount}
                </span>
                <span className="text-[10.5px] text-[rgba(0,0,0,0.4)]">
                  of {path.total} not confirmed safe
                </span>
              </div>

              {/* Coverage bar: filled portion is what is verified closed off. */}
              <div className="mt-2 h-1 w-full bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                <div
                  className={
                    openCount === 0
                      ? 'h-full bg-emerald-500'
                      : confirmedOpen > 0
                        ? 'h-full bg-red-400'
                        : 'h-full bg-[rgba(0,0,0,0.2)]'
                  }
                  style={{ width: `${coverage}%` }}
                />
              </div>

              <div className="mt-2 flex items-center justify-between gap-2">
                {confirmedOpen > 0 ? (
                  <span
                    className={`flex items-center gap-1 text-[10.5px] uppercase tracking-wide ${severityToken(path.worstSeverity).text}`}
                  >
                    <Icon icon={severityToken(path.worstSeverity).icon} width={11} />
                    {path.failed} failing · {path.worstSeverity}
                  </span>
                ) : path.unverified > 0 ? (
                  <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">
                    {path.unverified} unverified
                  </span>
                ) : (
                  <span className="text-[10.5px] font-medium text-emerald-600 uppercase tracking-wide">
                    Verified closed
                  </span>
                )}
                {path.affectedResources > 0 && (
                  <span className="text-[10px] text-[rgba(0,0,0,0.35)] shrink-0 tabular-nums">
                    {path.affectedResources} res
                  </span>
                )}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default WarAttackPathBoard;
