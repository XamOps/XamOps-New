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
  setPreset: (p: SavingsPreset) => void
  setCustomRange: (start: string, end: string) => void
  load: () => void
}

export function useClientSavings(clientId: number): UseClientSavingsReturn {
  const [data, setData]          = useState<ClientSavingsDashboardData | null>(null)
  const [isLoading, setLoading]  = useState(false)
  const [error, setError]        = useState<string | null>(null)
  const [preset, setPresetState] = useState<SavingsPreset>('30d')
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd]     = useState('')
  const hasLoaded = useRef(false)

  const fetchData = useCallback(async (p: SavingsPreset, start: string, end: string) => {
    setLoading(true)
    setError(null)
    try {
      let url = `/api/xamops/billops-admin/clients/${clientId}/savings`
      if (p !== 'custom') {
        url += `?preset=${p}`
      } else {
        url += `?startDate=${encodeURIComponent(start)}&endDate=${encodeURIComponent(end)}`
      }
      const response = await apiClient.get<ClientSavingsDashboardData>(url)
      setData(response)
    } catch (e: any) {
      setError(e?.message ?? 'Failed to load savings data')
    } finally {
      setLoading(false)
    }
  }, [clientId])

  const load = useCallback(() => {
    hasLoaded.current = true
    fetchData(preset, customStart, customEnd)
  }, [fetchData, preset, customStart, customEnd])

  const setPreset = useCallback((p: SavingsPreset) => {
    setPresetState(p)
    if (hasLoaded.current) fetchData(p, customStart, customEnd)
  }, [fetchData, customStart, customEnd])

  const setCustomRange = useCallback((start: string, end: string) => {
    setCustomStart(start)
    setCustomEnd(end)
    setPresetState('custom')
    if (hasLoaded.current) fetchData('custom', start, end)
  }, [fetchData])

  return { data, isLoading, error, preset, customStart, customEnd, setPreset, setCustomRange, load }
}
