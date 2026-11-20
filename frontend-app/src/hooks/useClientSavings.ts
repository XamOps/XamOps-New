import { useState, useCallback, useRef } from 'react'
import apiClient from '../utils/apiClient'
import { ClientSavingsDashboardData, SavingsPreset } from '../types/clientSavings'

interface UseClientSavingsReturn {
  data: ClientSavingsDashboardData | null
  isLoading: boolean
  error: string | null
  preset: SavingsPreset
  customStart: string
  customEnd: string
  /** The account the figures are filtered to; null for all accounts. */
  accountId: number | null
  setPreset: (p: SavingsPreset) => void
  setCustomRange: (start: string, end: string) => void
  setAccountId: (id: number | null) => void
  load: () => void
}

export function useClientSavings(clientId: number): UseClientSavingsReturn {
  const [data, setData]          = useState<ClientSavingsDashboardData | null>(null)
  const [isLoading, setLoading]  = useState(false)
  const [error, setError]        = useState<string | null>(null)
  const [preset, setPresetState] = useState<SavingsPreset>('30d')
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd]     = useState('')
  const [accountId, setAccountIdState] = useState<number | null>(null)
  const hasLoaded = useRef(false)
  // Switching account or range quickly can return responses out of order; only the newest counts.
  const latestRequest = useRef(0)

  const fetchData = useCallback(async (p: SavingsPreset, start: string, end: string, account: number | null) => {
    const request = ++latestRequest.current
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams()
      if (p !== 'custom') {
        params.set('preset', p)
      } else {
        params.set('startDate', start)
        params.set('endDate', end)
      }
      if (account != null) params.set('accountId', String(account))
      const response = await apiClient.get<ClientSavingsDashboardData>(
        `/api/xamops/billops-admin/clients/${clientId}/savings?${params.toString()}`)
      if (request === latestRequest.current) setData(response)
    } catch (e: any) {
      if (request === latestRequest.current) setError(e?.message ?? 'Failed to load savings data')
    } finally {
      if (request === latestRequest.current) setLoading(false)
    }
  }, [clientId])

  const load = useCallback(() => {
    hasLoaded.current = true
    fetchData(preset, customStart, customEnd, accountId)
  }, [fetchData, preset, customStart, customEnd, accountId])

  const setPreset = useCallback((p: SavingsPreset) => {
    setPresetState(p)
    if (hasLoaded.current) fetchData(p, customStart, customEnd, accountId)
  }, [fetchData, customStart, customEnd, accountId])

  const setCustomRange = useCallback((start: string, end: string) => {
    setCustomStart(start)
    setCustomEnd(end)
    setPresetState('custom')
    // Wait for both ends: a half-filled range would ask the server for an invalid window.
    if (hasLoaded.current && start && end) fetchData('custom', start, end, accountId)
  }, [fetchData, accountId])

  const setAccountId = useCallback((id: number | null) => {
    setAccountIdState(id)
    if (hasLoaded.current) fetchData(preset, customStart, customEnd, id)
  }, [fetchData, preset, customStart, customEnd])

  return {
    data, isLoading, error, preset, customStart, customEnd, accountId,
    setPreset, setCustomRange, setAccountId, load,
  }
}
