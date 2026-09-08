import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  listCurSources,
  reconcileAccount,
  registerCurSource,
  validateCurSource,
} from '../../api/curSourceApi';
import type { CurReconciliation, CurSource, CurSourceRegistration } from '../../types/curSource';

/**
 * Admin panel for the Cost and Usage Reports XamOps can read.
 *
 * Deliberately exposes reconciliation as prominently as registration. A report that
 * validated is not yet a report that can be trusted: the CUR and Cost Explorer
 * totals have to agree before accounts are switched over, because a user changing a
 * date filter can move between the two sources and must not see the total jump.
 */

const inputCls =
  'w-full px-3 py-2 text-[12.5px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[12px] font-medium text-[rgba(0,0,0,0.65)] mb-1';

const EMPTY_FORM: CurSourceRegistration = {
  name: '',
  ownerAccountId: '',
  xamopsOwned: true,
  s3Bucket: '',
  s3Prefix: 'cur',
  reportName: '',
  region: 'us-east-1',
  assumeRoleArn: '',
};

const StatusPill: React.FC<{ status: CurSource['status'] }> = ({ status }) => {
  const styles: Record<CurSource['status'], string> = {
    ACTIVE: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    PENDING: 'bg-amber-50 text-amber-700 border-amber-200',
    FAILED: 'bg-red-50 text-red-700 border-red-200',
  };
  return (
    <span className={`px-2 py-0.5 text-[10.5px] font-semibold rounded border ${styles[status]}`}>
      {status}
    </span>
  );
};

const CurSourcesPanel: React.FC = () => {
  const [sources, setSources] = useState<CurSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<CurSourceRegistration>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);

  const [reconcileFor, setReconcileFor] = useState('');
  const [reconcilePeriod, setReconcilePeriod] = useState('');
  const [reconcileResult, setReconcileResult] = useState<CurReconciliation | null>(null);
  const [reconciling, setReconciling] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setSources(await listCurSources());
      setError('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      // Registration validates and provisions the Athena table in the same call, so
      // the row that comes back already says whether the report is usable.
      const created = await registerCurSource({
        ...form,
        assumeRoleArn: form.assumeRoleArn?.trim() || undefined,
      });
      setSources((prev) => [...prev, created]);
      setForm(EMPTY_FORM);
      setShowForm(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleValidate = async (id: number) => {
    setBusyId(id);
    setError('');
    try {
      const updated = await validateCurSource(id);
      setSources((prev) => prev.map((s) => (s.id === id ? updated : s)));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  };

  const handleReconcile = async (e: React.FormEvent) => {
    e.preventDefault();
    setReconciling(true);
    setReconcileResult(null);
    setError('');
    try {
      setReconcileResult(await reconcileAccount(reconcileFor.trim(), reconcilePeriod || undefined));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setReconciling(false);
    }
  };

  const money = (n: number) =>
    n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 });

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-[15px] font-bold text-black">Cost &amp; Usage Reports</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Reports XamOps reads detailed billing data from. One payer report covers every
            account under it.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowForm((v) => !v)}
          className="px-4 py-2 bg-black text-white text-[12.5px] font-medium rounded-lg hover:bg-[rgba(0,0,0,0.85)] transition-colors flex items-center gap-2 flex-shrink-0"
        >
          <Icon icon={showForm ? 'mdi:close' : 'mdi:plus'} width={15} />
          {showForm ? 'Cancel' : 'Add report'}
        </button>
      </div>

      {error && (
        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-red-50 border border-red-100 rounded-lg">
          <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 flex-shrink-0 mt-0.5" />
          <span className="text-[12px] text-red-700 leading-relaxed">{error}</span>
        </div>
      )}

      {showForm && (
        <form
          onSubmit={handleRegister}
          className="p-5 border border-[rgba(0,0,0,0.1)] rounded-xl bg-[rgba(0,0,0,0.015)] space-y-4"
        >
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="curName" className={labelCls}>Name</label>
              <input
                id="curName"
                className={inputCls}
                placeholder="Xammer MPA (Mumbai)"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                required
              />
            </div>
            <div>
              <label htmlFor="curOwner" className={labelCls}>Owning account ID</label>
              <input
                id="curOwner"
                className={inputCls}
                placeholder="605134457560"
                value={form.ownerAccountId}
                onChange={(e) => setForm({ ...form, ownerAccountId: e.target.value })}
                required
              />
            </div>
            <div>
              <label htmlFor="curBucketAdmin" className={labelCls}>S3 bucket</label>
              <input
                id="curBucketAdmin"
                className={inputCls}
                placeholder="xamops-cur-605134457560"
                value={form.s3Bucket}
                onChange={(e) => setForm({ ...form, s3Bucket: e.target.value })}
                required
              />
            </div>
            <div>
              <label htmlFor="curPrefixAdmin" className={labelCls}>Prefix</label>
              <input
                id="curPrefixAdmin"
                className={inputCls}
                placeholder="cur"
                value={form.s3Prefix ?? ''}
                onChange={(e) => setForm({ ...form, s3Prefix: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor="curReportAdmin" className={labelCls}>Report name</label>
              <input
                id="curReportAdmin"
                className={inputCls}
                placeholder="xamops-cur"
                value={form.reportName}
                onChange={(e) => setForm({ ...form, reportName: e.target.value })}
                required
              />
            </div>
            <div>
              <label htmlFor="curRegionAdmin" className={labelCls}>Bucket region</label>
              <input
                id="curRegionAdmin"
                className={inputCls}
                placeholder="us-east-1"
                value={form.region}
                onChange={(e) => setForm({ ...form, region: e.target.value })}
                required
              />
            </div>
          </div>

          <label className="flex items-center gap-2.5 text-[12.5px] text-[rgba(0,0,0,0.7)]">
            <input
              type="checkbox"
              checked={form.xamopsOwned}
              onChange={(e) => setForm({ ...form, xamopsOwned: e.target.checked })}
              className="w-4 h-4 rounded border-[rgba(0,0,0,0.25)]"
            />
            The bucket is in a XamOps account
          </label>

          {form.xamopsOwned && (
            <div>
              <label htmlFor="curRoleArn" className={labelCls}>
                Assume role ARN <span className="font-normal text-[rgba(0,0,0,0.4)]">(optional)</span>
              </label>
              <input
                id="curRoleArn"
                className={inputCls}
                placeholder="arn:aws:iam::222233334444:role/XamOpsCurReadRole"
                value={form.assumeRoleArn ?? ''}
                onChange={(e) => setForm({ ...form, assumeRoleArn: e.target.value })}
              />
              {/* The one setting people get wrong with more than one payer account: the
                  app's own credentials only reach the account it runs in. */}
              <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1 leading-relaxed">
                Leave blank only for the payer account XamOps itself runs in. Every other payer
                account is a separate AWS account and needs a role we can assume.
              </p>
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="px-4 py-2 bg-black text-white text-[12.5px] font-medium rounded-lg hover:bg-[rgba(0,0,0,0.85)] transition-colors disabled:opacity-50 flex items-center gap-2"
          >
            {submitting ? (
              <>
                <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                Validating…
              </>
            ) : (
              'Register & validate'
            )}
          </button>
        </form>
      )}

      {loading ? (
        <div className="py-10 text-center text-[12.5px] text-[rgba(0,0,0,0.4)]">Loading reports…</div>
      ) : sources.length === 0 ? (
        <div className="py-10 text-center">
          <Icon icon="mdi:file-document-outline" width={28} className="text-[rgba(0,0,0,0.2)] mx-auto mb-2" />
          <p className="text-[12.5px] text-[rgba(0,0,0,0.45)]">
            No reports connected. Accounts read from the Cost Explorer API until one is added.
          </p>
        </div>
      ) : (
        <div className="space-y-2.5">
          {sources.map((source) => (
            <div key={source.id} className="p-4 border border-[rgba(0,0,0,0.1)] rounded-xl">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="flex items-center gap-2.5">
                    <p className="text-[13px] font-semibold text-black truncate">{source.name}</p>
                    <StatusPill status={source.status} />
                    {source.xamopsOwned && (
                      <span className="px-2 py-0.5 text-[10.5px] font-medium rounded border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)]">
                        Payer
                      </span>
                    )}
                  </div>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-1 font-mono truncate">
                    s3://{source.s3Bucket}/{source.s3Prefix ? `${source.s3Prefix}/` : ''}
                    {source.reportName}
                  </p>

                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-[11px] text-[rgba(0,0,0,0.5)]">
                    {source.timeUnit && <span>{source.timeUnit}</span>}
                    {source.format && <span>{source.format}</span>}
                    {source.earliestBillingPeriod && <span>from {source.earliestBillingPeriod}</span>}
                    {/* Both gate real features, so they are stated rather than implied. */}
                    <span className={source.resourceIdsEnabled ? 'text-emerald-700' : ''}>
                      {source.resourceIdsEnabled ? 'resource IDs' : 'no resource IDs'}
                    </span>
                    {source.splitCostDataEnabled && <span className="text-emerald-700">container costs</span>}
                  </div>

                  {source.status === 'FAILED' && source.statusDetail && (
                    <p className="text-[11.5px] text-red-700 mt-2 leading-relaxed">{source.statusDetail}</p>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => void handleValidate(source.id)}
                  disabled={busyId === source.id}
                  className="px-3 py-1.5 text-[12px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-50 flex items-center gap-1.5 flex-shrink-0"
                >
                  {busyId === source.id ? (
                    <div className="w-3 h-3 border-2 border-black border-t-transparent rounded-full animate-spin" />
                  ) : (
                    <Icon icon="mdi:refresh" width={14} />
                  )}
                  Revalidate
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Reconciliation ────────────────────────────────────────────────────── */}
      <div className="pt-5 border-t border-[rgba(0,0,0,0.08)]">
        <h3 className="text-[13.5px] font-bold text-black">Reconcile against Cost Explorer</h3>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5 mb-3.5 leading-relaxed">
          Compare both sources for one closed month before switching an account over. The open
          month is rewritten several times a day on both sides, so only a closed month proves
          anything.
        </p>

        <form onSubmit={handleReconcile} className="flex items-end gap-3 flex-wrap">
          <div className="flex-1 min-w-[180px]">
            <label htmlFor="reconcileAccount" className={labelCls}>AWS account ID</label>
            <input
              id="reconcileAccount"
              className={inputCls}
              placeholder="123456789012"
              value={reconcileFor}
              onChange={(e) => setReconcileFor(e.target.value)}
              required
            />
          </div>
          <div className="w-40">
            <label htmlFor="reconcilePeriod" className={labelCls}>Month</label>
            <input
              id="reconcilePeriod"
              className={inputCls}
              placeholder="2026-07"
              value={reconcilePeriod}
              onChange={(e) => setReconcilePeriod(e.target.value)}
            />
          </div>
          <button
            type="submit"
            disabled={reconciling}
            className="px-4 py-2 border border-[rgba(0,0,0,0.15)] text-[12.5px] font-medium rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-50"
          >
            {reconciling ? 'Comparing…' : 'Compare'}
          </button>
        </form>

        {reconcileResult && (
          <div
            className={[
              'mt-4 p-4 rounded-xl border',
              reconcileResult.withinTolerance
                ? 'bg-emerald-50 border-emerald-200'
                : 'bg-red-50 border-red-200',
            ].join(' ')}
          >
            <div className="flex items-center gap-2 mb-3">
              <Icon
                icon={reconcileResult.withinTolerance ? 'mdi:check-circle' : 'mdi:alert-circle'}
                width={17}
                className={reconcileResult.withinTolerance ? 'text-emerald-600' : 'text-red-600'}
              />
              <p
                className={[
                  'text-[12.5px] font-semibold',
                  reconcileResult.withinTolerance ? 'text-emerald-900' : 'text-red-900',
                ].join(' ')}
              >
                {reconcileResult.withinTolerance
                  ? 'Sources agree — safe to switch this account'
                  : 'Sources disagree — do not switch until explained'}
              </p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-[11.5px]">
              <div>
                <p className="text-[rgba(0,0,0,0.45)]">Report</p>
                <p className="font-semibold text-black mt-0.5">{money(reconcileResult.curTotal)}</p>
              </div>
              <div>
                <p className="text-[rgba(0,0,0,0.45)]">Cost Explorer</p>
                <p className="font-semibold text-black mt-0.5">
                  {money(reconcileResult.costExplorerTotal)}
                </p>
              </div>
              <div>
                <p className="text-[rgba(0,0,0,0.45)]">Difference</p>
                <p className="font-semibold text-black mt-0.5">{money(reconcileResult.difference)}</p>
              </div>
              <div>
                <p className="text-[rgba(0,0,0,0.45)]">Relative</p>
                <p className="font-semibold text-black mt-0.5">
                  {reconcileResult.relativeDifferencePercent.toFixed(2)}%
                </p>
              </div>
            </div>
            {!reconcileResult.withinTolerance && (
              <p className="text-[11.5px] text-red-800 mt-3 leading-relaxed">
                A gap of a few percent is usually credits or tax being counted on one side only.
                Check the charge-type breakdown for this account before proceeding.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default CurSourcesPanel;
