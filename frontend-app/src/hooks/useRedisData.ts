import { useState, useEffect } from 'react';
import apiClient from '../utils/apiClient';

export interface RedisInstanceMetrics {
  dsnAlias: string;
  dbHost: string;
  usedMemoryBytes: number;
  maxMemoryBytes: number;
  memUsagePct: number;
  keyspaceHits: number;
  keyspaceMisses: number;
  hitRatioPct: number;
  evictedKeys: number;
  expiredKeys: number;
  totalCommandsProcessed: number;
  connectedClients: number;
  role: string;
  replLagSeconds: number;
  uptimeSeconds: number;
  version: string;
  collectedAt: string;
}

export interface RedisSlowLog {
  dsnAlias: string;
  dbHost: string;
  id: number;
  timestamp: string;
  durationUs: number;
  command: string;
  collectedAt: string;
}

export interface RedisKeyspace {
  dsnAlias: string;
  dbHost: string;
  database: string;
  keys: number;
  expires: number;
  avgTtlMs: number;
  collectedAt: string;
}

export function useRedisData(accountId: string, dbHost?: string) {
  const [metrics, setMetrics] = useState<RedisInstanceMetrics[]>([]);
  const [slowLog, setSlowLog] = useState<RedisSlowLog[]>([]);
  const [keyspace, setKeyspace] = useState<RedisKeyspace[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!accountId) return;
    setLoading(true);
    const base = `/api/dbops/aws/${accountId}`;
    Promise.all([
      apiClient.get<RedisInstanceMetrics[]>(`${base}/agent-redis-metrics`),
      apiClient.get<RedisSlowLog[]>(`${base}/agent-redis-slow-log`),
      apiClient.get<RedisKeyspace[]>(`${base}/agent-redis-keyspace`),
    ])
      .then(([m, s, k]) => {
        const filter = <T extends { dbHost?: string }>(arr: T[]) =>
          dbHost
            ? arr.filter((x) => x.dbHost === dbHost || x.dbHost?.includes(dbHost))
            : arr;
        setMetrics(filter(m ?? []));
        setSlowLog(filter(s ?? []));
        setKeyspace(filter(k ?? []));
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [accountId, dbHost]);

  return { metrics, slowLog, keyspace, loading };
}
