export type SavingsPreset = '7d' | '30d' | '90d' | 'custom'

export interface SavingsByCloud {
  aws: number
  gcp: number
  azure: number
}

/** A money amount from the ledger: a decimal string (the API never sends a float). Older cached responses send a number. */
export type Decimal = string | number

export interface DailyPoint {
  date: string
  awsSpot: Decimal
  gcpSpot: Decimal
  awsEbs: number
}

/** One account's savings. Potential is per month; realized is over the requested range. */
export interface AccountSavings {
  accountId: number
  accountName: string | null
  provider: string | null
  providerAccountId: string | null
  rightsizing: number
  reservations: number
  /** Idle resources, disk rightsizing, AutoSpotting / spot MIG and CloudSitter estimates. */
  other: number
  potentialTotal: number
  realizedTotal: number
  /** Oldest snapshot behind this account's potential figures; null when there is none. */
  snapshotDate: string | null
}

/** An account the savings view can be filtered to. */
export interface SavingsAccountOption {
  id: number
  name: string
  provider: string
  providerAccountId: string | null
}

export interface ClientSavingsDashboardData {
  // Spot figures come from the savings ledger, in USD. "Realized" is closed AND invoiceable statements only, so
  // it is 0 until a month is invoiceable. The three spot figures below are never added into one another.
  realizedSpotAws: Decimal
  realizedSpotGcp: Decimal
  // The months behind the realized figure, e.g. "2026-08".
  realizedSpotMonths?: string[] | null
  // Closed statements that are not invoiceable (shadow months): shown only as "Not invoiced".
  shadowNotInvoicedAws?: Decimal | null
  shadowNotInvoicedGcp?: Decimal | null
  // The open month: shown only as "Accruing (not final)".
  accruingSpotAws?: Decimal | null
  accruingSpotGcp?: Decimal | null
  // How the daily Spot points are measured: "Daily accrual, before statement rounding".
  spotSeriesLabel?: string | null
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
  // Absent from responses built before the column existed.
  potentialOtherAws?: number
  potentialOtherGcp?: number
  potentialOtherAzure?: number

  realizedTotal: number
  potentialTotal: number
  grandTotal: number

  timeSeries: DailyPoint[]

  /** One row per account in scope. Absent from responses built before per-account rows existed. */
  accounts?: AccountSavings[]
  /** The account the figures are filtered to; null for all accounts. */
  selectedAccountId?: number | null

  startDate: string
  endDate: string
  clientName: string
  accountCount: number
  cacheRefreshedAt: string | null
}
