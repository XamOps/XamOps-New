import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { listAvailableCurSources, type AvailableCurSource } from '../../api/curSourceApi';
import type { AwsBillingSource } from '../../types/curSource';

/**
 * Chooses where an AWS account's cost data comes from.
 *
 * The four options are not cosmetic: picking the wrong one is the single failure
 * this whole feature is designed around. An account pointed at a report that does
 * not contain it produces a query that succeeds and matches nothing, which renders
 * as $0 spend — indistinguishable from an account that genuinely spent nothing.
 * So "no CUR is possible" is an explicit choice here rather than the absence of one.
 */

export interface CurSourceSelection {
  billingSource: AwsBillingSource;
  /** Set for XAMMER_MPA_CUR — which of the connected payer reports this account sits under. */
  curSourceId?: number;
  /** Set for OWN_CUR / EXISTING_CUR. */
  bucket?: string;
  prefix?: string;
  reportName?: string;
  region?: string;
}

interface CurSourceSelectorProps {
  value: CurSourceSelection;
  onChange: (selection: CurSourceSelection) => void;
}

const inputCls =
  'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

interface OptionMeta {
  key: AwsBillingSource;
  title: string;
  blurb: string;
  icon: string;
}

const OPTIONS: OptionMeta[] = [
  {
    key: 'XAMMER_MPA_CUR',
    title: 'Under a XamOps payer account',
    blurb:
      'This account is billed through us. Its costs are already in that payer account’s report — nothing to deploy.',
    icon: 'mdi:office-building-outline',
  },
  {
    key: 'OWN_CUR',
    title: 'Create a report in this account',
    blurb:
      'A standalone account, or one whose payer we don’t manage. Deploys a small stack that creates the report and its bucket.',
    icon: 'mdi:cloud-plus-outline',
  },
  {
    key: 'EXISTING_CUR',
    title: 'Use a report they already have',
    blurb:
      'Point us at an existing report instead of paying to store a second copy. We read what it contains rather than assuming.',
    icon: 'mdi:link-variant',
  },
  {
    key: 'COST_EXPLORER_ONLY',
    title: 'No report available',
    blurb:
      'Use the Cost Explorer API. Choose this when the payer never enabled linked-account billing access, so no report is possible.',
    icon: 'mdi:api',
  },
];

const CurSourceSelector: React.FC<CurSourceSelectorProps> = ({ value, onChange }) => {
  const [payerSources, setPayerSources] = useState<AvailableCurSource[]>([]);
  const [loadError, setLoadError] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [curStackUrl, setCurStackUrl] = useState<string>('');
  /** Why no launch link, or empty when there is one. Carries the reason, not just a flag. */
  const [curStackUnavailable, setCurStackUnavailable] = useState('');

  useEffect(() => {
    let cancelled = false;
    listAvailableCurSources()
      .then((all) => {
        if (!cancelled) setPayerSources(all);
      })
      .catch((e: Error) => {
        if (!cancelled) setLoadError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // The launch URL must pin us-east-1 (AWS::CUR::ReportDefinition exists nowhere else) and
  // the template location differs per environment, so the backend builds it.
  //
  // The failure reason is captured rather than collapsed into one message. An earlier
  // version reported every failure as "template not published", which was wrong and
  // actively misleading: the template was published, and the real cause was the dev
  // server having no proxy entry for /api/cloudformation — so the fetch returned the
  // SPA's own index.html and JSON parsing threw. Reporting the status distinguishes
  // "not configured" (503) from "never reached the backend" (HTML, 404, network).
  useEffect(() => {
    if (value.billingSource !== 'OWN_CUR' || curStackUrl) return;
    let cancelled = false;

    fetch('/api/cloudformation/cur-deploy-url', { credentials: 'include' })
      .then(async (r) => {
        if (!r.ok) {
          throw new Error(
            r.status === 503
              ? 'no cost reporting template is configured for this environment'
              : `HTTP ${r.status}`,
          );
        }
        // A dev proxy gap answers with index.html and a 200, so a content-type check is
        // what separates "backend said no" from "request never got there".
        const contentType = r.headers.get('content-type') ?? '';
        if (!contentType.includes('application/json')) {
          throw new Error('the request did not reach the backend');
        }
        return r.json();
      })
      .then((data) => {
        if (cancelled) return;
        if (data?.url) {
          setCurStackUrl(data.url as string);
        } else {
          setCurStackUnavailable('the backend returned no launch URL');
        }
      })
      .catch((e: Error) => {
        if (!cancelled) setCurStackUnavailable(e.message);
      });

    return () => {
      cancelled = true;
    };
  }, [value.billingSource, curStackUrl]);

  const set = (patch: Partial<CurSourceSelection>) => onChange({ ...value, ...patch });

  const selectOption = (key: AwsBillingSource) => {
    // Clear fields belonging to the previous option so a stale bucket from an
    // abandoned choice cannot be submitted with a different billing source.
    onChange({
      billingSource: key,
      curSourceId: key === 'XAMMER_MPA_CUR' ? payerSources[0]?.id : undefined,
    });
  };

  const needsReportDetails = value.billingSource === 'EXISTING_CUR';

  return (
    <div className="space-y-5">
      <div>
        <h3 className="text-[14px] font-bold text-black mb-1">Cost data source</h3>
        <p className="text-[12px] text-[rgba(0,0,0,0.45)]">
          Detailed billing data comes from a Cost and Usage Report. Where that report lives
          depends on how this account is billed.
        </p>
      </div>

      <div className="space-y-2.5">
        {OPTIONS.map((option) => {
          const selected = value.billingSource === option.key;
          const mpaEmpty = option.key === 'XAMMER_MPA_CUR' && !loading && !loadError && payerSources.length === 0;
          const mpaBroken = option.key === 'XAMMER_MPA_CUR' && !loading && !!loadError;
          const mpaUnavailable = mpaEmpty || mpaBroken;

          return (
            <div key={option.key}>
              <button
                type="button"
                onClick={() => !mpaUnavailable && selectOption(option.key)}
                disabled={mpaUnavailable}
                aria-pressed={selected}
                className={[
                  'w-full text-left px-4 py-3.5 rounded-lg border transition-colors flex items-start gap-3',
                  selected
                    ? 'border-black bg-[rgba(0,0,0,0.02)]'
                    : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.25)]',
                  mpaUnavailable ? 'opacity-45 cursor-not-allowed' : '',
                ].join(' ')}
              >
                <div
                  className={[
                    'w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5',
                    selected ? 'bg-black' : 'bg-[rgba(0,0,0,0.04)]',
                  ].join(' ')}
                >
                  <Icon
                    icon={option.icon}
                    width={17}
                    className={selected ? 'text-white' : 'text-[rgba(0,0,0,0.45)]'}
                  />
                </div>
                <div className="min-w-0">
                  <p className="text-[13px] font-semibold text-black">{option.title}</p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] mt-0.5 leading-relaxed">
                    {option.blurb}
                  </p>
                  {mpaEmpty && (
                    <p className="text-[11px] text-amber-700 mt-1.5">
                      No payer report is connected yet — add one in CUR Sources first.
                    </p>
                  )}
                  {mpaBroken && (
                    <p className="text-[11px] text-red-700 mt-1.5">
                      Couldn&rsquo;t load payer reports, so this can&rsquo;t be chosen. {loadError}
                    </p>
                  )}
                </div>
                {selected && (
                  <Icon icon="mdi:check-circle" width={18} className="text-black flex-shrink-0 ml-auto mt-0.5" />
                )}
              </button>

              {/* Which payer report, when more than one is connected. */}
              {selected && option.key === 'XAMMER_MPA_CUR' && payerSources.length > 0 && (
                <div className="mt-2.5 ml-11 pl-0.5">
                  <label htmlFor="curSourceId" className={labelCls}>
                    Payer account report
                  </label>
                  <select
                    id="curSourceId"
                    className={inputCls}
                    value={value.curSourceId ?? ''}
                    onChange={(e) => set({ curSourceId: Number(e.target.value) })}
                  >
                    {payerSources.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name} ({s.ownerAccountId})
                      </option>
                    ))}
                  </select>
                  <p className={hintCls}>
                    We verify this account actually appears in the report before switching it over.
                  </p>
                </div>
              )}

              {/* Where to get the stack, for the create-a-report option. */}
              {selected && option.key === 'OWN_CUR' && (
                <div className="mt-2.5 ml-11 pl-0.5 space-y-3">
                  <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
                    <Icon
                      icon="mdi:information-outline"
                      width={15}
                      className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
                    />
                    <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
                      Deploy in <span className="font-medium">us-east-1</span> — AWS only accepts
                      report definitions there. The first delivery takes up to 24 hours, and the
                      report starts from the current month rather than backfilling history. Until it
                      lands, we serve this account from the Cost Explorer API.
                    </div>
                  </div>
                  {curStackUrl && (
                    <a
                      href={curStackUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-2 px-4 py-2 bg-black text-white text-[12.5px] font-medium rounded-lg hover:bg-[rgba(0,0,0,0.85)] transition-colors"
                    >
                      <Icon icon="mdi:rocket-launch-outline" width={15} />
                      Launch cost reporting stack
                    </a>
                  )}
                  {/* Without this the option is selectable but offers no way to act on it,
                      which reads as a broken page rather than an unconfigured environment. */}
                  {curStackUnavailable && (
                    <div className="flex items-start gap-2.5 px-3.5 py-3 bg-amber-50 border border-amber-200 rounded-lg">
                      <Icon
                        icon="mdi:alert-outline"
                        width={15}
                        className="text-amber-600 flex-shrink-0 mt-0.5"
                      />
                      <div className="text-[11.5px] text-amber-900 leading-relaxed">
                        No launch link — {curStackUnavailable}. The role stack below still grants
                        access to the report once it exists, so you can deploy{' '}
                        <span className="font-medium">xamops-cur-report.yml</span> in us-east-1
                        yourself, or pick another option.
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Coordinates of an existing report. */}
              {selected && needsReportDetails && (
                <div className="mt-2.5 ml-11 pl-0.5 space-y-4">
                  <div>
                    <label htmlFor="curBucket" className={labelCls}>
                      S3 bucket
                    </label>
                    <input
                      id="curBucket"
                      className={inputCls}
                      placeholder="my-cur-bucket"
                      value={value.bucket ?? ''}
                      onChange={(e) => set({ bucket: e.target.value })}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label htmlFor="curPrefix" className={labelCls}>
                        Prefix
                      </label>
                      <input
                        id="curPrefix"
                        className={inputCls}
                        placeholder="cur"
                        value={value.prefix ?? ''}
                        onChange={(e) => set({ prefix: e.target.value })}
                      />
                      <p className={hintCls}>Leave blank if delivered to the bucket root</p>
                    </div>
                    <div>
                      <label htmlFor="curRegion" className={labelCls}>
                        Bucket region
                      </label>
                      <input
                        id="curRegion"
                        className={inputCls}
                        placeholder="us-east-1"
                        value={value.region ?? ''}
                        onChange={(e) => set({ region: e.target.value })}
                      />
                      <p className={hintCls}>Where the bucket is, not where the report was defined</p>
                    </div>
                  </div>
                  <div>
                    <label htmlFor="curReportName" className={labelCls}>
                      Report name
                    </label>
                    <input
                      id="curReportName"
                      className={inputCls}
                      placeholder="my-cur"
                      value={value.reportName ?? ''}
                      onChange={(e) => set({ reportName: e.target.value })}
                    />
                    <p className={hintCls}>
                      Exactly as it appears in Billing → Cost &amp; Usage Reports
                    </p>
                  </div>
                  <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
                    <Icon
                      icon="mdi:magnify-scan"
                      width={15}
                      className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
                    />
                    <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
                      We read the report&rsquo;s manifest to find its granularity, format and whether
                      it includes resource IDs. Features that need more detail than it carries stay
                      hidden rather than failing — an hourly Parquet report with resource IDs
                      unlocks everything.
                    </div>
                  </div>
                </div>
              )}

              {/* What choosing CE actually costs them. */}
              {selected && option.key === 'COST_EXPLORER_ONLY' && (
                <div className="mt-2.5 ml-11 pl-0.5">
                  <div className="flex items-start gap-2.5 px-3.5 py-3 bg-amber-50 border border-amber-200 rounded-lg">
                    <Icon
                      icon="mdi:alert-outline"
                      width={15}
                      className="text-amber-600 flex-shrink-0 mt-0.5"
                    />
                    <div className="text-[11.5px] text-amber-900 leading-relaxed">
                      Spend totals and service breakdowns still work. Resource-level cost, container
                      cost allocation, effective savings rate and price-vs-volume analysis need a
                      report and will stay unavailable for this account.
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {loadError && (
        <div className="flex items-center gap-2 px-3 py-2.5 bg-red-50 border border-red-100 rounded-lg">
          <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 flex-shrink-0" />
          <span className="text-[12px] text-red-700">
            Could not load connected reports — {loadError}
          </span>
        </div>
      )}
    </div>
  );
};

export default CurSourceSelector;
