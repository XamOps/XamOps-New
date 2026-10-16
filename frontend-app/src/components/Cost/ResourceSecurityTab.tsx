import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import {
  fetchResourceSecurity,
  type ResourceSecurityFinding,
  type ResourceSecurityFindings,
} from '../../api/curInsightsApi';

/**
 * What SecOps surfaces know about this resource, and where to go to see each finding in full.
 *
 * <h2>Why this tab exists</h2>
 * The drawer's other tabs answer what a resource cost and what it is attached to. This one answers
 * the question a reader has the moment a resource looks expensive or unfamiliar: is anything wrong
 * with it. Security Center, ComplianceOps and Performance Insights each already know — but a reader
 * on the bill would have to leave, pick the right product, and filter it by hand to find out. This
 * tab brings those three answers to the resource, grouped by product and most-severe-first, and
 * every row deep-links back to the product page filtered to the resource so the full finding is one
 * click away.
 *
 * <h2>Where the answer comes from</h2>
 * One server call per focus. The aggregation lives on the server because the three products speak
 * three different id vocabularies and three different severity scales, and reconciling them in the
 * browser is the thing that drifts. This component sends the id exactly as the drawer holds it and
 * renders what comes back without re-sorting or re-normalising — the backend already did both.
 */
export interface SecurityFetchers {
  /** Resolves the resource's findings, or null when the request could not be read at all. */
  findings: (
    accountId: string,
    resourceId: string,
    region?: string,
    period?: string,
  ) => Promise<ResourceSecurityFindings | null>;
}

/** The AWS default. GCP and Azure pass `null` for the tab, so they never reach this. */
export const awsSecurity: SecurityFetchers = {
  findings: fetchResourceSecurity,
};

interface ResourceSecurityTabProps {
  /**
   * Whether this tab is the selected one. Passed in rather than derived, because the body is
   * mounted while hidden so a glance at another tab does not refetch — and a body that inferred its
   * own visibility would fetch while hidden.
   */
  active: boolean;
  accountId: string | null;
  resourceId: string | null;
  resourceName?: string;
  /** Billing month, `YYYY-MM`. Advisory for this read; threaded for symmetry with the sibling. */
  period: string;
  /** The row's region, omitted when the report gave none. */
  region?: string;
  security: SecurityFetchers;
}

/** The five normalised severities, worst first, mapped to a badge treatment. */
const SEVERITY_STYLE: Record<string, { label: string; className: string }> = {
  CRITICAL: { label: 'Critical', className: 'bg-red-50 text-red-700 border-red-200' },
  HIGH: { label: 'High', className: 'bg-orange-50 text-orange-700 border-orange-200' },
  MEDIUM: { label: 'Medium', className: 'bg-amber-50 text-amber-700 border-amber-200' },
  LOW: { label: 'Low', className: 'bg-sky-50 text-sky-700 border-sky-200' },
  INFORMATIONAL: { label: 'Info', className: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.12)]' },
};

const SeverityBadge: React.FC<{ severity: string }> = ({ severity }) => {
  const style = SEVERITY_STYLE[severity?.toUpperCase()] ?? SEVERITY_STYLE.MEDIUM;
  return (
    <span
      className={`inline-flex items-center px-1.5 py-0.5 rounded text-[10.5px] font-semibold uppercase tracking-wide border ${style.className}`}
    >
      {style.label}
    </span>
  );
};

/**
 * The react-router search string for a deep link's query.
 *
 * <p>Empty object → `''` so the target is the bare path with no trailing `?`. `URLSearchParams`
 * encodes each value, which is what keeps an ARN's colons and slashes intact in the URL.
 */
export const toSearch = (query: Record<string, string>): string => {
  const params = new URLSearchParams(query);
  const search = params.toString();
  return search ? `?${search}` : '';
};

const ResourceSecurityTab: React.FC<ResourceSecurityTabProps> = ({
  active,
  accountId,
  resourceId,
  period,
  region,
  security,
}) => {
  const navigate = useNavigate();

  const query = useQuery({
    queryKey: ['cost-security', 'findings', accountId ?? '', resourceId ?? '', region ?? ''],
    queryFn: () => security.findings(accountId as string, resourceId as string, region, period),
    enabled: active && Boolean(accountId) && Boolean(resourceId),
    staleTime: 5 * 60 * 1000,
  });

  // Silence before anything else: a query that has never fetched is neither loading nor empty, so a
  // hidden, unselected body must not render the empty state while it waits.
  if (!active) return null;

  if (query.isPending) {
    return (
      <p className="text-[12.5px] text-[rgba(0,0,0,0.5)] px-1 py-6" role="status" aria-live="polite">
        Reading this resource&apos;s findings…
      </p>
    );
  }

  if (query.isError || query.data === null) {
    return (
      <div className="text-center py-12">
        <Icon icon="mdi:shield-alert-outline" width={26} className="text-[rgba(0,0,0,0.2)] mx-auto mb-2" />
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
          The findings for this resource could not be read
        </p>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1">
          The other tabs are unaffected.
        </p>
        <button
          type="button"
          onClick={() => { void query.refetch(); }}
          className="mt-3 px-3 py-1.5 text-[11.5px] font-medium rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
        >
          Try again
        </button>
      </div>
    );
  }

  const { groups, errors } = query.data;

  if (groups.length === 0 && errors.length === 0) {
    return (
      <div className="text-center py-12">
        <Icon icon="mdi:shield-check-outline" width={26} className="text-emerald-500/70 mx-auto mb-2" />
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
          No findings reference this resource
        </p>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[420px] mx-auto leading-relaxed">
          Nothing in Security Center, ComplianceOps, Performance Insights or XamSecure points at this resource.
        </p>
      </div>
    );
  }

  const go = (finding: ResourceSecurityFinding) => {
    navigate({ pathname: finding.deepLink.path, search: toSearch(finding.deepLink.query) });
  };

  return (
    <div className="space-y-5">
      {groups.map((group) => (
        <section key={group.product}>
          <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium mb-1.5">
            {group.label} ({group.findings.length})
          </p>
          <ul className="space-y-1.5">
            {group.findings.map((finding, i) => (
              <li key={`${group.product}-${i}`}>
                <button
                  type="button"
                  onClick={() => go(finding)}
                  className="w-full text-left flex items-start gap-2.5 px-3 py-2.5 rounded-xl border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.02)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[#4285F4] transition-colors"
                >
                  <span className="mt-0.5 flex-shrink-0">
                    <SeverityBadge severity={finding.severity} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-[12.5px] font-medium text-black truncate" title={finding.title}>
                      {finding.title}
                    </span>
                    {finding.detail && (
                      <span className="block text-[11px] text-[rgba(0,0,0,0.45)] truncate" title={finding.detail}>
                        {finding.detail}
                      </span>
                    )}
                  </span>
                  <Icon
                    icon="mdi:arrow-top-right"
                    width={14}
                    className="mt-0.5 flex-shrink-0 text-[rgba(0,0,0,0.3)]"
                  />
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}

      {errors.length > 0 && (
        <div className="space-y-1.5">
          {errors.map((err) => (
            <p
              key={err.product}
              className="text-[11.5px] text-[#B45309] px-3 py-2 rounded-xl border border-[rgba(180,83,9,0.2)] bg-[rgba(180,83,9,0.04)]"
            >
              {err.message}
            </p>
          ))}
        </div>
      )}
    </div>
  );
};

export default ResourceSecurityTab;
