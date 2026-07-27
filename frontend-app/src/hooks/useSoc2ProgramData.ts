import { useState, useEffect, useCallback } from 'react';
import {
  fetchSoc2Overview, fetchSoc2Frameworks, fetchSoc2Controls, fetchSoc2Policies,
  fetchSoc2Tasks, fetchSoc2Personnel, fetchSoc2Vendors,
  fetchSoc2Risks, fetchSoc2CloudTests, fetchSoc2Integrations, fetchSoc2Documents,
  Soc2Overview, Soc2Framework, Soc2Control, Soc2Policy, Soc2Task, Soc2Person, Soc2Vendor,
  Soc2Risk, Soc2CloudTest, Soc2Integration, Soc2Document,
} from '../api/soc2Api';

/**
 * Loads all SOC2 Program data in parallel. Backed by Redis-cached
 * xamops-service endpoints (10-min TTL server-side), so this is a plain
 * fetch-on-mount without extra client caching.
 */
export const useSoc2ProgramData = () => {
  const [overview, setOverview] = useState<Soc2Overview | null>(null);
  const [frameworks, setFrameworks] = useState<Soc2Framework[]>([]);
  const [controls, setControls] = useState<Soc2Control[]>([]);
  const [policies, setPolicies] = useState<Soc2Policy[]>([]);
  const [tasks, setTasks] = useState<Soc2Task[]>([]);
  const [personnel, setPersonnel] = useState<Soc2Person[]>([]);
  const [vendors, setVendors] = useState<Soc2Vendor[]>([]);
  const [risks, setRisks] = useState<Soc2Risk[]>([]);
  const [cloudTests, setCloudTests] = useState<Soc2CloudTest[]>([]);
  const [integrations, setIntegrations] = useState<Soc2Integration[]>([]);
  const [documents, setDocuments] = useState<Soc2Document[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [ov, fw, ctrl, pol, tsk, ppl, vnd, rsk, cld, intg, docs] = await Promise.all([
        fetchSoc2Overview(),
        fetchSoc2Frameworks(),
        fetchSoc2Controls(),
        fetchSoc2Policies(),
        fetchSoc2Tasks(),
        fetchSoc2Personnel(),
        fetchSoc2Vendors(),
        fetchSoc2Risks(),
        fetchSoc2CloudTests(),
        fetchSoc2Integrations(),
        fetchSoc2Documents(),
      ]);
      setOverview(ov);
      setFrameworks(fw);
      setControls(ctrl);
      setPolicies(pol);
      setTasks(tsk);
      setPersonnel(ppl);
      setVendors(vnd);
      setRisks(rsk);
      setCloudTests(cld);
      setIntegrations(intg);
      setDocuments(docs);
    } catch (e) {
      setError(e instanceof Error ? e : new Error('Failed to load SOC2 program data'));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  return {
    overview, frameworks, controls, policies, tasks, personnel, vendors,
    risks, cloudTests, integrations, documents,
    isLoading, error, refetch: fetchData,
  };
};
