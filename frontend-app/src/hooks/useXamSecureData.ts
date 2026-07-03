import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import type {
  XamSecureSummary, ThreatDetection, VulnerabilityFinding, PostureFinding,
  ComplianceFramework, OnboardingAccount, PageResp,
} from '../types/xamsecure';

const CACHE_DURATION = 30 * 60 * 1000; // 30 min for scan-based tabs
const THREAT_POLL_MS = 60 * 1000;      // runtime feed

export function xamSecureCacheKey(section: string, accountId: string | null): string {
  return `xamsecure_${section}_${accountId ?? 'all'}`;
}

function loadCache<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    const expiry = localStorage.getItem(`${key}_expiry`);
    if (raw && expiry && Date.now() <= parseInt(expiry)) return JSON.parse(raw) as T;
  } catch { /* ignore */ }
  return null;
}

function saveCache(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    localStorage.setItem(`${key}_expiry`, (Date.now() + CACHE_DURATION).toString());
  } catch { /* ignore */ }
}

interface UseXamSecureDataParams {
  selectedAccountId: string | null;
  activeTab: string;
}

export const useXamSecureData = ({ selectedAccountId, activeTab }: UseXamSecureDataParams) => {
  const [summary, setSummary] = useState<XamSecureSummary | null>(null);
  const [threats, setThreats] = useState<PageResp<ThreatDetection> | null>(null);
  const [vulnerabilities, setVulnerabilities] = useState<PageResp<VulnerabilityFinding> | null>(null);
  const [posture, setPosture] = useState<PageResp<PostureFinding> | null>(null);
  const [compliance, setCompliance] = useState<ComplianceFramework[] | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingAccount[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const pollRef = useRef<number | null>(null);

  const acct = selectedAccountId ? `accountId=${encodeURIComponent(selectedAccountId)}&` : '';

  const loadSummary = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('summary', selectedAccountId);
    if (!force) {
      const cached = loadCache<XamSecureSummary>(key);
      if (cached) { setSummary(cached); return; }
    }
    const data = await apiClient.get<XamSecureSummary>(`/api/xamsecure/summary?${acct}`);
    setSummary(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadThreats = useCallback(async (page = 0, severity = '') => {
    const data = await apiClient.get<PageResp<ThreatDetection>>(
      `/api/xamsecure/threats?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setThreats(data);
  }, [acct]);

  const loadVulnerabilities = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`vulns_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<VulnerabilityFinding>>(key);
      if (cached) { setVulnerabilities(cached); return; }
    }
    const data = await apiClient.get<PageResp<VulnerabilityFinding>>(
      `/api/xamsecure/vulnerabilities?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setVulnerabilities(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadPosture = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`posture_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<PostureFinding>>(key);
      if (cached) { setPosture(cached); return; }
    }
    const data = await apiClient.get<PageResp<PostureFinding>>(
      `/api/xamsecure/posture?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setPosture(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadCompliance = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('compliance', selectedAccountId);
    if (!force) {
      const cached = loadCache<ComplianceFramework[]>(key);
      if (cached) { setCompliance(cached); return; }
    }
    const data = await apiClient.get<ComplianceFramework[]>(`/api/xamsecure/compliance?${acct}`);
    setCompliance(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadOnboarding = useCallback(async () => {
    const data = await apiClient.get<OnboardingAccount[]>('/api/xamsecure/onboarding/status');
    setOnboarding(data);
  }, []);

  // Load the active tab's data; Threats tab also polls every 60s.
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setIsLoading(true);
      try {
        if (activeTab === 'overview') await loadSummary();
        else if (activeTab === 'threats') await loadThreats();
        else if (activeTab === 'vulnerabilities') await loadVulnerabilities();
        else if (activeTab === 'posture') await loadPosture();
        else if (activeTab === 'compliance') await loadCompliance();
        else if (activeTab === 'onboarding') await loadOnboarding();
      } catch (err) {
        console.error('[XamSecure] load failed:', err);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    };
    run();

    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    if (activeTab === 'threats') {
      pollRef.current = window.setInterval(() => {
        loadThreats().catch((err) => console.error('[XamSecure] threat poll failed:', err));
      }, THREAT_POLL_MS);
    }
    return () => {
      cancelled = true;
      if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [activeTab, selectedAccountId, loadSummary, loadThreats, loadVulnerabilities,
      loadPosture, loadCompliance, loadOnboarding]);

  const refresh = useCallback(() => {
    if (activeTab === 'overview') return loadSummary(true);
    if (activeTab === 'threats') return loadThreats();
    if (activeTab === 'vulnerabilities') return loadVulnerabilities(0, '', true);
    if (activeTab === 'posture') return loadPosture(0, '', true);
    if (activeTab === 'compliance') return loadCompliance(true);
    if (activeTab === 'onboarding') return loadOnboarding();
    return Promise.resolve();
  }, [activeTab, loadSummary, loadThreats, loadVulnerabilities, loadPosture, loadCompliance, loadOnboarding]);

  return {
    summary, threats, vulnerabilities, posture, compliance, onboarding,
    isLoading, refresh,
    loadThreats, loadVulnerabilities, loadPosture,
  };
};
