/**
 * IdentityAccessCanvas
 *
 * The Security Center's Identity & Access tab: every IAM user and role in the selected
 * account laid out as a canvas of cards, worst-risk first, each expanding in place to show
 * what that identity can actually do.
 *
 * <p>This replaces the standalone User Management page, which rendered the same data as two
 * dense tables of policy <em>names</em>. Names do not tell you whether an identity is
 * over-privileged, so the page could list an account administrator and a read-only auditor
 * as visually identical rows. Cards make room for a verdict; the table did not.
 *
 * <p>Works for all three providers, but risk scoring is AWS-only today. Where the backend
 * returns no assessment the risk KPIs and red flags are hidden rather than shown as zero —
 * "0 over-privileged" is a claim, and the product should not make it on evidence it lacks.
 */

import React, { useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { IamIdentity } from '../../../types/userManagement';
import { identityKey } from '../../../types/userManagement';
import IdentityCard from './IdentityCard';
import { RISK_TOKENS } from './riskTokens';

type IdentityKind = 'users' | 'roles';

interface RiskCounts {
  critical: number;
  high: number;
  medium: number;
  low: number;
  flagged: number;
  assessed: number;
}

interface IdentityAccessCanvasProps {
  users: IamIdentity[];
  roles: IamIdentity[];
  totalUsers: number;
  totalRoles: number;
  isLoading: boolean;
  error?: string | null;
  searchQuery: string;
  onSearchChange: (value: string) => void;
  flaggedOnly: boolean;
  onFlaggedOnlyChange: (value: boolean) => void;
  riskCounts: RiskCounts;
  riskAnalysisAvailable: boolean;
  analysisDegraded: boolean;
  analysisNote?: string | null;
  noMfaCount: number;
  /** AWS | GCP | Azure — drives labels and whether MFA is meaningful. */
  provider: 'AWS' | 'GCP' | 'Azure';
  labels: { users: string; roles: string };
  formatDate: (date: string | null) => string;
  isOldDate: (date: string | null) => boolean;
  onCopy: (text: string) => void;
  /**
   * Forces a fresh IAM read.
   *
   * <p>Lives here rather than in the page topbar because the topbar's Refresh triggers a
   * compliance scan, which does not touch IAM inventory. Two buttons labelled "refresh" that
   * refresh different things belong next to the data they each affect.
   */
  onRefresh: () => void;
  isRefreshing: boolean;
}

const BEBAS = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '30px',
  fontWeight: 400,
  lineHeight: '30px',
  fontVariantNumeric: 'tabular-nums' as const,
};

const CardSkeleton = () => (
  <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm p-4">
    <div className="flex items-start gap-3">
      <div className="w-10 h-10 rounded-xl bg-[rgba(0,0,0,0.06)] animate-pulse flex-shrink-0" />
      <div className="flex-1">
        <div className="h-4 w-32 bg-[rgba(0,0,0,0.06)] animate-pulse rounded mb-2" />
        <div className="h-3 w-44 bg-[rgba(0,0,0,0.04)] animate-pulse rounded" />
      </div>
    </div>
  </div>
);

const IdentityAccessCanvas: React.FC<IdentityAccessCanvasProps> = ({
  users,
  roles,
  totalUsers,
  totalRoles,
  isLoading,
  error,
  searchQuery,
  onSearchChange,
  flaggedOnly,
  onFlaggedOnlyChange,
  riskCounts,
  riskAnalysisAvailable,
  analysisDegraded,
  analysisNote,
  noMfaCount,
  provider,
  labels,
  formatDate,
  isOldDate,
  onCopy,
  onRefresh,
  isRefreshing,
}) => {
  const [kind, setKind] = useState<IdentityKind>('users');

  /**
   * Which cards are open, by identity key.
   *
   * <p>A Set rather than a single id: comparing two over-privileged identities side by side is
   * the main reason to open more than one, and single-open accordions make that impossible.
   * Reset is intentionally not wired to filter changes — a card the user opened stays open if
   * it survives the filter, which is what makes narrowing to "flagged only" feel continuous.
   */
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const toggle = (key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const visible = kind === 'users' ? users : roles;
  const totalVisible = kind === 'users' ? totalUsers : totalRoles;

  /**
   * What an attached grant is called, per provider and per identity kind.
   *
   * <p>A role card lists permissions rather than grants on every provider, which is why the
   * kind is part of the decision and not just the provider.
   */
  const accessNoun = useMemo(() => {
    if (kind === 'roles') {
      return provider === 'AWS'
        ? { one: 'policy', many: 'policies' }
        : { one: 'permission', many: 'permissions' };
    }
    if (provider === 'GCP') return { one: 'role', many: 'roles' };
    if (provider === 'Azure') return { one: 'role', many: 'roles' };
    return { one: 'policy', many: 'policies' };
  }, [kind, provider]);

  const kpis = useMemo(() => {
    const cards: Array<{ label: string; value: number; sub: string; color: string; }> = [
      {
        label: 'Identities',
        value: totalUsers + totalRoles,
        sub: `${totalUsers} ${labels.users.toLowerCase()}, ${totalRoles} roles`,
        color: 'rgb(10,10,10)',
      },
    ];

    if (riskAnalysisAvailable) {
      cards.push(
        {
          label: 'Over-privileged',
          value: riskCounts.flagged,
          sub: 'holding excess access',
          color: RISK_TOKENS.CRITICAL.kpi,
        },
        {
          label: 'Critical',
          value: riskCounts.critical,
          sub: 'admin or self-escalating',
          color: RISK_TOKENS.CRITICAL.kpi,
        },
      );
    }

    if (provider === 'AWS') {
      cards.push({
        label: 'Without MFA',
        value: noMfaCount,
        sub: 'password-only users',
        color: noMfaCount > 0 ? RISK_TOKENS.HIGH.kpi : RISK_TOKENS.NONE.kpi,
      });
    }

    return cards;
  }, [totalUsers, totalRoles, labels.users, riskAnalysisAvailable, riskCounts, provider, noMfaCount]);

  return (
    <div className="space-y-5">
      {error && (
        <div className="flex items-start gap-3 px-4 py-3 bg-rose-50 border border-rose-100 rounded-xl">
          <Icon icon="mdi:alert-circle-outline" width={16} color="#F43F5E" className="flex-shrink-0 mt-0.5" />
          <p className="text-sm text-rose-700">{error}</p>
        </div>
      )}

      {/* The honesty banner. See the note on permissionAnalysisDegraded in the DTO. */}
      {analysisDegraded && analysisNote && (
        <div className="flex items-start gap-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-xl">
          <Icon icon="mdi:shield-alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-xs font-bold text-amber-800 uppercase tracking-wider mb-0.5">Partial assessment</p>
            <p className="text-sm text-amber-700">{analysisNote}</p>
          </div>
        </div>
      )}

      {/* All three providers are analyzed now, so this fires only when an audit returned nothing
          it could score — never as a standing provider limitation. Without it an unassessed list
          would read as a clean one, which is the failure mode the whole feature guards against. */}
      {!isLoading && !riskAnalysisAvailable && !analysisDegraded && (totalUsers + totalRoles) > 0 && (
        <div className="flex items-start gap-3 px-4 py-3 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.1)] rounded-xl">
          <Icon icon="mdi:information-outline" width={16} className="text-[rgba(0,0,0,0.45)] flex-shrink-0 mt-0.5" />
          <p className="text-sm text-[rgba(0,0,0,0.6)]">
            These identities have not been assessed for excess access yet. Reload to run the audit.
          </p>
        </div>
      )}

      {/* KPI strip */}
      {isLoading ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
              <div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-24 mb-3" />
              <div className="h-7 bg-[rgba(0,0,0,0.06)] rounded w-14" />
            </div>
          ))}
        </div>
      ) : (
        <div className={`grid grid-cols-2 gap-4 ${kpis.length >= 4 ? 'md:grid-cols-4' : 'md:grid-cols-3'}`}>
          {kpis.map((c) => (
            <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
              <p className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.5)] mb-2">{c.label}</p>
              <p style={{ ...BEBAS, color: c.color }}>{c.value}</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{c.sub}</p>
            </div>
          ))}
        </div>
      )}

      {/* Controls */}
      <div className="flex flex-col lg:flex-row lg:items-center gap-3">
        {/* Users / roles switch. A segmented control rather than nested tabs, because the page
            already spends its tab row on Findings vs Identity and two tab bars stacked read
            as a hierarchy that is not there. */}
        <div className="inline-flex p-1 bg-[rgba(0,0,0,0.04)] rounded-lg self-start">
          {([
            { id: 'users' as IdentityKind, label: labels.users, icon: 'mdi:account-outline', count: totalUsers },
            { id: 'roles' as IdentityKind, label: labels.roles, icon: 'mdi:shield-account-outline', count: totalRoles },
          ]).map((option) => (
            <button
              key={option.id}
              type="button"
              onClick={() => setKind(option.id)}
              aria-pressed={kind === option.id}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-semibold transition-colors ${
                kind === option.id
                  ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                  : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.75)]'
              }`}
            >
              <Icon icon={option.icon} width={13} />
              {option.label}
              <span className="text-[10px] px-1.5 py-0.5 bg-[rgba(0,0,0,0.06)] rounded-full font-bold text-[rgba(0,0,0,0.5)]">
                {option.count}
              </span>
            </button>
          ))}
        </div>

        <div className="relative flex-1">
          <div className="absolute inset-y-0 left-3 flex items-center pointer-events-none">
            <Icon icon="mdi:magnify" width={14} color="rgba(0,0,0,0.35)" />
          </div>
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder={`Search ${labels.users.toLowerCase()} and roles by name or ID...`}
            aria-label="Search identities"
            className="w-full pl-9 pr-4 py-2 text-xs bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.3)] focus:border-[rgba(0,0,0,0.3)]"
          />
        </div>

        {riskAnalysisAvailable && (
          <button
            type="button"
            onClick={() => onFlaggedOnlyChange(!flaggedOnly)}
            aria-pressed={flaggedOnly}
            className={`flex items-center gap-2 px-3 py-2 text-xs font-semibold rounded-lg border transition-colors self-start ${
              flaggedOnly
                ? 'bg-red-500 border-red-500 text-white'
                : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.65)] hover:border-red-200 hover:text-red-600'
            }`}
          >
            <Icon icon="mdi:flag" width={13} />
            Over-privileged only
            <span className={`text-[10px] px-1.5 py-0.5 rounded-full font-bold ${
              flaggedOnly ? 'bg-white/25 text-white' : 'bg-red-50 text-red-600'
            }`}>
              {riskCounts.flagged}
            </span>
          </button>
        )}

        <button
          type="button"
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-2 text-xs font-semibold rounded-lg border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-50 self-start"
        >
          <Icon icon="mdi:sync" width={13} className={isRefreshing ? 'animate-spin' : ''} />
          {isRefreshing ? 'Reloading...' : 'Reload identities'}
        </button>
      </div>

      {/* Canvas */}
      {isLoading ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4">
          {[...Array(6)].map((_, i) => <CardSkeleton key={i} />)}
        </div>
      ) : visible.length > 0 ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start">
          {visible.map((identity) => {
            const key = identityKey(identity);
            return (
              <IdentityCard
                key={key}
                identity={identity}
                isExpanded={expanded.has(key)}
                onToggle={() => toggle(key)}
                showRisk={riskAnalysisAvailable}
                showMfa={provider === 'AWS'}
                accessNoun={accessNoun}
                formatDate={formatDate}
                isOldDate={isOldDate}
                onCopy={onCopy}
              />
            );
          })}
        </div>
      ) : (
        <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon
              icon={flaggedOnly ? 'mdi:shield-check' : 'mdi:account-off-outline'}
              width={28}
              color="rgba(0,0,0,0.25)"
            />
          </div>
          {/* Three distinct empty states, because they mean opposite things: a clean result, a
              search miss, and an account with nothing in it. */}
          {flaggedOnly && totalVisible > 0 ? (
            <>
              <p className="text-sm font-bold text-[rgba(0,0,0,0.6)]">No over-privileged {kind}</p>
              <p className="text-xs text-[rgba(0,0,0,0.45)] mt-1">
                Nothing here holds excess access. Clear the filter to see all {totalVisible}.
              </p>
            </>
          ) : searchQuery && totalVisible > 0 ? (
            <>
              <p className="text-sm font-bold text-[rgba(0,0,0,0.6)]">No matches</p>
              <p className="text-xs text-[rgba(0,0,0,0.45)] mt-1">
                No {kind} match “{searchQuery}”.
              </p>
            </>
          ) : (
            <p className="text-sm font-bold text-[rgba(0,0,0,0.5)]">
              No {kind === 'users' ? labels.users.toLowerCase() : 'roles'} found
            </p>
          )}
        </div>
      )}
    </div>
  );
};

export default IdentityAccessCanvas;
