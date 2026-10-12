export type SavingsPreset = '7d' | '30d' | '90d' | 'custom'

export interface SavingsByCloud {
  aws: number
  gcp: number
  azure: number
}

export interface DailyPoint {
  date: string
  awsSpot: number
  gcpSpot: number
  awsEbs: number
}

export interface ClientSavingsDashboardData {
  // Spot reads only verified (non-legacy) rows, so it is 0 until the savings ledger exists.
  realizedSpotAws: number
  realizedSpotGcp: number
  realizedEbsAws: number

  // Legacy rows in the range. Shown only as "Legacy (unverified)" and never added into a total.
  // Absent from responses cached before this field existed.
  legacySpotUnverifiedAws?: number | null
  legacySpotUnverifiedGcp?: number | null

  potentialRightsizingAws: number
  potentialRightsizingGcp: number
  potentialRightsizingAzure: number
  potentialReservationsAws: number
  potentialReservationsGcp: number
  potentialReservationsAzure: number

  realizedTotal: number
  potentialTotal: number
  grandTotal: number

  timeSeries: DailyPoint[]

  startDate: string
  endDate: string
  clientName: string
  accountCount: number
  cacheRefreshedAt: string | null
}
