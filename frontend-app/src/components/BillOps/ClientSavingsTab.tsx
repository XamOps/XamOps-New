import { useEffect } from 'react'
import {
  BarChart, Bar, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip,
  Legend, ResponsiveContainer
} from 'recharts'
import { useClientSavings } from '../../hooks/useClientSavings'
import { SavingsPreset } from '../../types/clientSavings'

interface Props {
  clientId: number
}

const fmt = (n: number) =>
  n === 0 ? '—' : `$${n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`

export default function ClientSavingsTab({ clientId }: Props) {
  const {
    data, isLoading, error,
    preset, customStart, customEnd,
    setPreset, setCustomRange, load
  } = useClientSavings(clientId)

  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <SavingsSkeleton />
  if (error)     return <div className="p-4 text-red-500 text-sm">{error}</div>
  if (!data)     return null

  let cumulative = 0
  const cumulativeData = data.timeSeries.map(p => {
    cumulative += p.awsSpot + p.gcpSpot + p.awsEbs
    return { date: p.date, cumulative }
  })

  const totalRightsizing =
    data.potentialRightsizingAws + data.potentialRightsizingGcp + data.potentialRightsizingAzure
  const totalReservations =
    data.potentialReservationsAws + data.potentialReservationsGcp + data.potentialReservationsAzure

  return (
    <div className="p-4 space-y-6">

      {/* Time Range Picker */}
      <div className="flex items-center gap-2 flex-wrap">
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

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <KpiCard label="Total Savings"  value={fmt(data.grandTotal)}        sub="Realized + Estimated"                                                                         realized />
        <KpiCard label="Realized Savings" value={fmt(data.realizedTotal)}      sub="Realized · across clouds"                                                                     realized />
        <KpiCard label="Rightsizing"    value={`~${fmt(totalRightsizing)}`}  sub={data.cacheRefreshedAt ? `Potential · as of ${data.cacheRefreshedAt}` : 'Potential (est.)'}  realized={false} />
        <KpiCard label="Reservations"   value={`~${fmt(totalReservations)}`} sub={data.cacheRefreshedAt ? `Potential · as of ${data.cacheRefreshedAt}` : 'Potential (est.)'}  realized={false} />
      </div>

      {/* 3x3 Breakdown Grid */}
      <div>
        <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Savings Breakdown</h4>
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="bg-gray-50 text-gray-500 text-xs">
                <th className="text-left p-2 border border-gray-200"></th>
                <th className="text-right p-2 border border-gray-200">Spot (Realized)</th>
                <th className="text-right p-2 border border-gray-200">
                  Rightsizing <span className="text-gray-400 cursor-help" title="From last cached dashboard refresh. Open cloud dashboards to update.">~est.</span>
                </th>
                <th className="text-right p-2 border border-gray-200">
                  Reservations <span className="text-gray-400 cursor-help" title="From last cached dashboard refresh. Open cloud dashboards to update.">~est.</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <GridRow label="AWS"   spot={data.realizedSpotAws + data.realizedEbsAws} rightsizing={data.potentialRightsizingAws}   reservations={data.potentialReservationsAws} />
              <GridRow label="GCP"   spot={data.realizedSpotGcp}                        rightsizing={data.potentialRightsizingGcp}   reservations={data.potentialReservationsGcp} />
              <GridRow label="Azure" spot={0}                                           rightsizing={data.potentialRightsizingAzure} reservations={data.potentialReservationsAzure} />
              <tr className="bg-gray-50 font-semibold text-xs">
                <td className="p-2 border border-gray-200 text-gray-700">Total</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">
                  {fmt(data.realizedSpotAws + data.realizedSpotGcp + data.realizedEbsAws)}
                </td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalRightsizing)}</td>
                <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(totalReservations)}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* Charts */}
      <div className="space-y-6">
        <div>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Daily Realized Savings</h4>
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
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Cumulative Savings</h4>
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

function KpiCard({ label, value, sub, realized }: {
  label: string; value: string; sub: string; realized: boolean
}) {
  return (
    <div className="premium-glass rounded-lg p-3 border border-gray-100">
      <div className="text-xs text-gray-500 mb-1">{label}</div>
      <div className={`text-lg font-bold ${realized ? 'text-gray-900' : 'text-blue-700'}`}>{value}</div>
      <div className="text-xs text-gray-400 mt-1">{sub}</div>
    </div>
  )
}

function GridRow({ label, spot, rightsizing, reservations }: {
  label: string; spot: number; rightsizing: number; reservations: number
}) {
  return (
    <tr className="hover:bg-gray-50 text-xs">
      <td className="p-2 border border-gray-200 font-medium text-gray-700">{label}</td>
      <td className="p-2 border border-gray-200 text-right text-gray-900">{fmt(spot)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(rightsizing)}</td>
      <td className="p-2 border border-gray-200 text-right text-blue-700">{fmt(reservations)}</td>
    </tr>
  )
}

function SavingsSkeleton() {
  return (
    <div className="p-4 space-y-4 animate-premium-pulse">
      <div className="grid grid-cols-4 gap-3">
        {[...Array(4)].map((_, i) => <div key={i} className="h-20 bg-gray-100 rounded-lg" />)}
      </div>
      <div className="h-32 bg-gray-100 rounded-lg" />
      <div className="h-48 bg-gray-100 rounded-lg" />
    </div>
  )
}
