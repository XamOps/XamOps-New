import { useEffect, useMemo } from 'react'
import {
  BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer
} from 'recharts'
import { useClientSavings } from '../../hooks/useClientSavings'
import { Decimal, SavingsAccountOption, SavingsPreset } from '../../types/clientSavings'

import LegacyUnverifiedTag from '../SpotAutomation/LegacyUnverifiedTag'

interface Props {
  clientId: number
  /** The client's accounts, for the account filter. Without them the filter is hidden. */
  accounts?: SavingsAccountOption[]
}

/** Whole dollars, except under $10 where cents matter (a $0.55 saving must not read as $1). */
const fmt = (n: number) => {
  if (!n) return '—'
  const digits = Math.abs(n) < 10 ? 2 : 0
  return `$${n.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`
}
// Ledger amounts arrive as decimal strings; this is display only, no money is computed from it.
const num = (v: Decimal | null | undefined): number => (v === null || v === undefined || v === '' ? 0 : Number(v))
const fmtCents = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

const OTHER_HINT =
  'Idle resources, disk rightsizing, and the AutoSpotting, spot MIG and CloudSitter estimates: ' +
  'what the provider dashboards count as potential savings beyond rightsizing and reservations.'
const ESTIMATE_HINT = 'From the last cached dashboard refresh. Open the cloud dashboards to update.'

const providerBadge = (provider: string | null) =>
  provider === 'AWS' ? 'bg-orange-50 text-orange-700 border-orange-200'
    : provider === 'GCP' ? 'bg-blue-50 text-blue-700 border-blue-200'
      : 'bg-emerald-50 text-emerald-700 border-emerald-200'

export default function ClientSavingsTab({ clientId, accounts = [] }: Props) {
  const {
    data, isLoading, error,
    preset, customStart, customEnd, accountId,
    setPreset, setCustomRange, setAccountId, load
  } = useClientSavings(clientId)

  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Accounts without a provider ID have no snapshots, so filtering to one would only show dashes.
  const accountOptions = useMemo(
    () => [...accounts].sort((a, b) => a.name.localeCompare(b.name)),
    [accounts])
  const selectable = accountOptions.filter(a => a.providerAccountId)

  const controls = (
    <div className="flex items-center gap-2 flex-wrap">
      {accountOptions.length > 0 && (
        <>
          <label className="text-xs text-gray-500" htmlFor={`savings-account-${clientId}`}>Account:</label>
          <select
            id={`savings-account-${clientId}`}
            value={accountId ?? ''}
            onChange={e => setAccountId(e.target.value === '' ? null : Number(e.target.value))}
            className="text-xs border border-gray-300 rounded px-2 py-1 bg-white max-w-[260px]"
          >
            <option value="">All accounts ({selectable.length})</option>
            {accountOptions.map(a => (
              <option key={a.id} value={a.id} disabled={!a.providerAccountId}>
                {a.name} · {a.provider}{a.providerAccountId ? '' : ' (no account ID)'}
              </option>
            ))}
          </select>
          <span className="text-gray-400 text-xs mx-1">|</span>
        </>
      )}
      {(['7d', '30d', '90d'] as SavingsPreset[]).map(p => (
        <button
          key={p}
          onClick={() => setPreset(p)}
          className={`px-3 py-1 text-xs rounded-full border transition-colors ${
            preset === p
              ? 'bg-blue-600 text-white border-blue-600'
              : 'bg-white text-gray-600 border-gray-300 hover:border-blue-400'
          }`}
        >
          {p === '7d' ? 'Last 7 days' : p === '30d' ? 'Last 30 days' : 'Last 90 days'}
        </button>
      ))}
      <span className="text-gray-400 text-xs mx-1">|</span>
      <span className="text-xs text-gray-500">Custom:</span>
      <input
        type="date"
        value={customStart}
        max={customEnd || undefined}
        onChange={e => setCustomRange(e.target.value, customEnd)}
        className="text-xs border border-gray-300 rounded px-2 py-1"
      />
      <span className="text-xs text-gray-400">→</span>
      <input
        type="date"
        value={customEnd}
        min={customStart || undefined}
        onChange={e => setCustomRange(customStart, e.target.value)}
        className="text-xs border border-gray-300 rounded px-2 py-1"
      />
    </div>
  )

  // Keep the controls in place while reloading, so changing the account or range doesn't
  // make the filter itself disappear.
  if (!data) {
    return (
      <div className="p-4 space-y-6">
        {controls}
        {error ? <div className="text-red-500 text-sm">{error}</div> : isLoading ? <SavingsSkeleton /> : null}
      </div>
    )
  }

  let cumulative = 0
  const cumulativeData = data.timeSeries.map(p => {
    cumulative += num(p.awsSpot) + num(p.gcpSpot) + p.awsEbs
    return { date: p.date, cumulative }
  })

  const otherAws = data.potentialOtherAws ?? 0
  const otherGcp = data.potentialOtherGcp ?? 0
  const otherAzure = data.potentialOtherAzure ?? 0

  const totalRightsizing =
    data.potentialRightsizingAws + data.potentialRightsizingGcp + data.potentialRightsizingAzure
  const totalReservations =
    data.potentialReservationsAws + data.potentialReservationsGcp + data.potentialReservationsAzure
  const totalOther = otherAws + otherGcp + otherAzure
  const totalPotential = totalRightsizing + totalReservations + totalOther


  // Legacy spot savings: shown in their own row, never added into a total above.
  const legacySpotAws = data.legacySpotUnverifiedAws ?? 0
  const legacySpotGcp = data.legacySpotUnverifiedGcp ?? 0
  const hasLegacySpot = legacySpotAws > 0 || legacySpotGcp > 0
  const realizedSpotAws = num(data.realizedSpotAws)
  const realizedSpotGcp = num(data.realizedSpotGcp)
  const realizedAws = realizedSpotAws + data.realizedEbsAws
  const realizedGcp = realizedSpotGcp
  // Shadow and open-month Spot figures: their own rows, never a total and never "Realized".
  const notInvoicedAws = num(data.shadowNotInvoicedAws)
  const notInvoicedGcp = num(data.shadowNotInvoicedGcp)
  const accruingAws = num(data.accruingSpotAws)
  const accruingGcp = num(data.accruingSpotGcp)
  const hasNotInvoiced = notInvoicedAws !== 0 || notInvoicedGcp !== 0
  const hasAccruing = accruingAws !== 0 || accruingGcp !== 0
  const realizedMonths = data.realizedSpotMonths ?? []
  const seriesLabel = data.spotSeriesLabel ?? 'Daily accrual, before statement rounding'

  const asOf = data.cacheRefreshedAt ? `as of ${data.cacheRefreshedAt}` : 'no snapshot yet'
  const showAccountTable = accountId == null && (data.accounts?.length ?? 0) > 1
  const accountRows = [...(data.accounts ?? [])].sort((a, b) => b.potentialTotal - a.potentialTotal)

  return (
    <div className={`p-4 space-y-6 transition-opacity ${isLoading ? 'opacity-60' : ''}`} aria-busy={isLoading}>
      {controls}
      {error && <div className="text-red-500 text-sm">{error}</div>}

      {/* Potential and realized are kept apart: one is per month from the latest snapshot, the
          other is money already saved over the chosen range. Adding them gives a meaningless number. */}
      <div>
        <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
          Potential savings <span className="normal-case font-normal text-gray-400">· per month · {asOf}</span>
        </h4>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <KpiCard label="Total potential" value={fmt(totalPotential)} sub="Per month, if acted on" emphasis />
          <KpiCard label="Rightsizing"     value={fmt(totalRightsizing)}  sub="Resize recommendations" />
          <KpiCard label="Reservations"    value={fmt(totalReservations)} sub="Reserved instances, CUDs" />
          <KpiCard label="Other optimizations" value={fmt(totalOther)} sub="Idle, disk, spot, scheduling" title={OTHER_HINT} />
        </div>
      </div>

      <div>
        <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">
          Realized savings <span className="normal-case font-normal text-gray-400">· {data.startDate} to {data.endDate}</span>
        </h4>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <KpiCard label="Realized" value={fmt(data.realizedTotal)} sub="Spot conversions + EBS actions" />
        </div>
      </div>

      {/* Breakdown by cloud */}
      <div>
        <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Savings Breakdown</h4>
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="bg-gray-50 text-gray-500 text-xs">
                <th className="text-left p-2 border border-gray-200"></th>
                <th className="text-right p-2 border border-gray-200">
                  Rightsizing <span className="text-gray-400 cursor-help" title={ESTIMATE_HINT}>~est.</span>
                </th>
                <th className="text-right p-2 border border-gray-200">
                  Reservations <span className="text-gray-400 cursor-help" title={ESTIMATE_HINT}>~est.</span>
                </th>
                <th className="text-right p-2 border border-gray-200">
                  <span className="cursor-help" title={OTHER_HINT}>Other</span> <span className="text-gray-400 cursor-help" title={ESTIMATE_HINT}>~est.</span>
                </th>
                <th className="text-right p-2 border border-gray-200">Potential / mo</th>
                <th className="text-right p-2 border border-gray-200 border-l-2 border-l-gray-300">Realized</th>
              </tr>
            </thead>
            <tbody>
              <GridRow label="AWS"   rightsizing={data.potentialRightsizingAws}   reservations={data.potentialReservationsAws}   other={otherAws}   realized={realizedAws} />
              <GridRow label="GCP"   rightsizing={data.potentialRightsizingGcp}   reservations={data.potentialReservationsGcp}   other={otherGcp}   realized={realizedGcp} />
              <GridRow label="Azure" rightsizing={data.potentialRightsizingAzure} reservations={data.potentialReservationsAzure} other={otherAzure} realized={0} />
              <tr className="bg-gray-50 font-semibold text-xs">
                <td className="p-2 border border-gray-200 text-gray-700">Total</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalRightsizing)}</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalReservations)}</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalOther)}</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalPotential)}</td>
                <td className="p-2 border border-gray-200 border-l-2 border-l-gray-300 text-right text-gray-900">{fmt(data.realizedTotal)}</td>
              </tr>
            </tbody>
          </table>
        </div>
        {realizedMonths.length > 0 && (
          <p data-testid="realized-months" className="mt-2 text-xs text-gray-500">
            Spot realized = closed statements for {realizedMonths.join(', ')}
          </p>
        )}
        {hasNotInvoiced && (
          <div
            data-testid="not-invoiced-row"
            className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 rounded border border-gray-200 px-2 py-1.5 text-xs text-gray-600"
          >
            <span className="font-medium text-gray-700">Spot</span>
            <span>AWS <span className="text-gray-900">{fmtCents(notInvoicedAws)}</span></span>
            <span>GCP <span className="text-gray-900">{fmtCents(notInvoicedGcp)}</span></span>
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700">Not invoiced</span>
            <span className="text-gray-400">Not in totals</span>
          </div>
        )}
        {hasAccruing && (
          <div
            data-testid="accruing-row"
            className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 rounded border border-gray-200 px-2 py-1.5 text-xs text-gray-600"
          >
            <span className="font-medium text-gray-700">Spot</span>
            <span>AWS <span className="text-gray-900">{fmtCents(accruingAws)}</span></span>
            <span>GCP <span className="text-gray-900">{fmtCents(accruingGcp)}</span></span>
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700">Accruing (not final)</span>
            <span className="text-gray-400">Not in totals</span>
          </div>
        )}
        {hasLegacySpot && (
          <div
            data-testid="legacy-spot-row"
            className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 rounded border border-gray-200 px-2 py-1.5 text-xs text-gray-600"
          >
            <span className="font-medium text-gray-700">Spot</span>
            <span>AWS <span className="text-gray-900">{fmt(legacySpotAws)}</span></span>
            <span>GCP <span className="text-gray-900">{fmt(legacySpotGcp)}</span></span>
            <LegacyUnverifiedTag />
            <span className="text-gray-400">Not in totals</span>
          </div>
        )}
      </div>

      {/* Per-account potential, so the total can be traced to the accounts behind it. */}
      {showAccountTable && (
        <div data-testid="account-savings-table">
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">By Account</h4>
          <div className="overflow-x-auto">
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="bg-gray-50 text-gray-500 text-xs">
                  <th className="text-left p-2 border border-gray-200">Account</th>
                  <th className="text-right p-2 border border-gray-200">Rightsizing</th>
                  <th className="text-right p-2 border border-gray-200">Reservations</th>
                  <th className="text-right p-2 border border-gray-200">Other</th>
                  <th className="text-right p-2 border border-gray-200">Potential / mo</th>
                  <th className="text-right p-2 border border-gray-200">Snapshot</th>
                </tr>
              </thead>
              <tbody>
                {accountRows.map(row => {
                  const canFilter = !!row.providerAccountId
                  return (
                    <tr
                      key={row.accountId}
                      onClick={canFilter ? () => setAccountId(row.accountId) : undefined}
                      title={canFilter ? 'Show only this account' : 'No account ID, so no snapshots to read'}
                      className={`text-xs ${canFilter ? 'cursor-pointer hover:bg-indigo-50/40' : 'text-gray-400'}`}
                    >
                      <td className="p-2 border border-gray-200">
                        <span className={`mr-2 text-[10px] font-semibold px-1.5 py-0.5 rounded border uppercase ${providerBadge(row.provider)}`}>
                          {row.provider}
                        </span>
                        <span className="font-medium text-gray-700">{row.accountName ?? `#${row.accountId}`}</span>
                      </td>
                      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(row.rightsizing)}</td>
                      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(row.reservations)}</td>
                      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(row.other)}</td>
                      <td className="p-2 border border-gray-200 text-right font-semibold text-gray-900">{fmt(row.potentialTotal)}</td>
                      <td className="p-2 border border-gray-200 text-right text-gray-500">{row.snapshotDate ?? '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Charts: realized only */}
      <div className="space-y-6">
        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">{seriesLabel}</h4>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data.timeSeries} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={(d: string) => d.slice(5)} />
              <YAxis tick={{ fontSize: 10 }} tickFormatter={(v: number) => `$${v}`} />
              <Tooltip formatter={(v: any) => [`$${Number(v).toFixed(2)}`, undefined]} />
              <Legend iconSize={10} wrapperStyle={{ fontSize: 11 }} />
              <Bar dataKey="awsSpot"   name="AWS Spot"   stackId="a" fill="#f59e0b" />
              <Bar dataKey="gcpSpot"   name="GCP Spot"   stackId="a" fill="#3b82f6" />
              <Bar dataKey="awsEbs"    name="AWS EBS"    stackId="a" fill="#10b981" />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Cumulative accrual (not final)</h4>
          <ResponsiveContainer width="100%" height={160}>
            <LineChart data={cumulativeData} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="date" tick={{ fontSize: 10 }} tickFormatter={(d: string) => d.slice(5)} />
              <YAxis tick={{ fontSize: 10 }} tickFormatter={(v: number) => `$${v}`} />
              <Tooltip formatter={(v: any) => [`$${Number(v).toFixed(2)}`, 'Cumulative']} />
              <Line type="monotone" dataKey="cumulative" stroke="#2563eb" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  )
}

function KpiCard({ label, value, sub, emphasis = false, title }: {
  label: string; value: string; sub: string; emphasis?: boolean; title?: string
}) {
  return (
    <div className="premium-glass rounded-lg p-3 border border-gray-100" title={title}>
      <div className="text-xs text-gray-500 mb-1">{label}</div>
      <div className={`text-lg font-bold ${emphasis ? 'text-blue-700' : 'text-gray-900'}`}>{value}</div>
      <div className="text-xs text-gray-400 mt-1">{sub}</div>
    </div>
  )
}

function GridRow({ label, rightsizing, reservations, other, realized }: {
  label: string; rightsizing: number; reservations: number; other: number; realized: number
}) {
  return (
    <tr className="hover:bg-gray-50 text-xs">
      <td className="p-2 border border-gray-200 font-medium text-gray-700">{label}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(rightsizing)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(reservations)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(other)}</td>
      <td className="p-2 border border-gray-200 text-right font-semibold text-gray-900">{fmt(rightsizing + reservations + other)}</td>
      <td className="p-2 border border-gray-200 border-l-2 border-l-gray-300 text-right text-gray-900">{fmt(realized)}</td>
    </tr>
  )
}

function SavingsSkeleton() {
  return (
    <div className="space-y-4 animate-premium-pulse">
      <div className="grid grid-cols-4 gap-3">
        {[...Array(4)].map((_, i) => <div key={i} className="h-20 bg-gray-100 rounded-lg" />)}
      </div>
      <div className="h-32 bg-gray-100 rounded-lg" />
      <div className="h-48 bg-gray-100 rounded-lg" />
    </div>
  )
}
