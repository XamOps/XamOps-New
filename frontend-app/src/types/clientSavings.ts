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
  azureSpot: number
  awsEbs: number
}

export interface ClientSavingsDashboardData {
  realizedSpotAws: number
  realizedSpotGcp: number
  realizedSpotAzure: number
  realizedEbsAws: number

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
