import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { RoutingRule, RoutingRuleCreateRequest, RoutingStats, RoutingHistoryEntry } from '../types/aiops';

export function useRoutingData(period: string) {
  const [rules, setRules] = useState<RoutingRule[]>([]);
  const [stats, setStats] = useState<RoutingStats | null>(null);
  const [history, setHistory] = useState<RoutingHistoryEntry[]>([]);
  const [rulesLoading, setRulesLoading] = useState(false);
  const [statsLoading, setStatsLoading] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadRules = useCallback(async () => {
    setRulesLoading(true);
    try {
      const data = await apiFetchJson<RoutingRule[]>('/api/xamops/aiops/routing/rules');
      setRules(data);
    } catch (err) {
      console.error('Failed to load routing rules', err);
    } finally {
      setRulesLoading(false);
    }
  }, []);

  const loadStats = useCallback(async () => {
    setStatsLoading(true);
    try {
      const data = await apiFetchJson<RoutingStats>(`/api/xamops/aiops/routing/stats?period=${period}`);
      setStats(data);
    } catch (err) {
      console.error('Failed to load routing stats', err);
    } finally {
      setStatsLoading(false);
    }
  }, [period]);

  const loadHistory = useCallback(async (page = 0, size = 20) => {
    setHistoryLoading(true);
    try {
      const data = await apiFetchJson<RoutingHistoryEntry[]>(
        `/api/xamops/aiops/routing/history?page=${page}&size=${size}`
      );
      setHistory(data);
    } catch (err) {
      console.error('Failed to load routing history', err);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  const createRule = useCallback(async (req: RoutingRuleCreateRequest) => {
    setSaving(true);
    try {
      await apiFetchJson<RoutingRule>('/api/xamops/aiops/routing/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      });
      await loadRules();
    } catch (err) {
      console.error('Failed to create routing rule', err);
    } finally {
      setSaving(false);
    }
  }, [loadRules]);

  const updateRule = useCallback(async (id: number, req: RoutingRuleCreateRequest) => {
    setSaving(true);
    try {
      await apiFetchJson<RoutingRule>(`/api/xamops/aiops/routing/rules/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      });
      await loadRules();
    } catch (err) {
      console.error('Failed to update routing rule', err);
    } finally {
      setSaving(false);
    }
  }, [loadRules]);

  const toggleRule = useCallback(async (id: number, enabled: boolean) => {
    try {
      await apiFetchJson<void>(`/api/xamops/aiops/routing/rules/${id}/toggle?enabled=${enabled}`, {
        method: 'PUT',
      });
      setRules(prev => prev.map(r => r.id === id ? { ...r, enabled } : r));
    } catch (err) {
      console.error('Failed to toggle routing rule', err);
    }
  }, []);

  const deleteRule = useCallback(async (id: number) => {
    try {
      await apiFetchJson<void>(`/api/xamops/aiops/routing/rules/${id}`, {
        method: 'DELETE',
      });
      setRules(prev => prev.filter(r => r.id !== id));
    } catch (err) {
      console.error('Failed to delete routing rule', err);
    }
  }, []);

  return {
    rules, stats, history,
    rulesLoading, statsLoading, historyLoading, saving,
    loadRules, loadStats, loadHistory,
    createRule, updateRule, toggleRule, deleteRule,
  };
}
