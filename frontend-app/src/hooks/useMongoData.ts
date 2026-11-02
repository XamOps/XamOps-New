import { useState, useEffect } from 'react';
import apiClient from '../utils/apiClient';

export interface MongoInstanceMetrics {
  dsnAlias: string;
  dbHost: string;
  currentConnections: number;
  availableConnections: number;
  totalCreatedConnections: number;
  residentMemMb: number;
  virtualMemMb: number;
  opsInsert: number;
  opsQuery: number;
  opsUpdate: number;
  opsDelete: number;
  opsGetmore: number;
  opsCommand: number;
  networkBytesIn: number;
  networkBytesOut: number;
  uptimeSeconds: number;
  version: string;
  collectedAt: string;
}

export interface MongoSlowOp {
  dsnAlias: string;
  dbHost: string;
  namespace: string;
  op: string;
  queryDigest: string;
  millis: number;
  timestamp: string;
  nReturned: number;
  nScanned: number;
  collectedAt: string;
}

export interface MongoIndexStat {
  dsnAlias: string;
  dbHost: string;
  database: string;
  collection: string;
  indexName: string;
  accessOps: number;
  accessSince: string;
  collectedAt: string;
}

export function useMongoData(accountId: string, dbHost?: string) {
  const [metrics, setMetrics] = useState<MongoInstanceMetrics[]>([]);
  const [slowOps, setSlowOps] = useState<MongoSlowOp[]>([]);
  const [indexStats, setIndexStats] = useState<MongoIndexStat[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // PERF-19: only fetch when a Mongo host is supplied. The drawer passes a host only for a Mongo
    // instance whose mirror flag is on, so a Postgres/MySQL drawer no longer fires three unused
    // Mongo requests on every open.
    if (!accountId || !dbHost) {
      setMetrics([]);
      setSlowOps([]);
      setIndexStats([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    const base = `/api/dbops/aws/${accountId}`;
    Promise.all([
      apiClient.get<MongoInstanceMetrics[]>(`${base}/agent-mongo-metrics`),
      apiClient.get<MongoSlowOp[]>(`${base}/agent-mongo-slow-ops`),
      apiClient.get<MongoIndexStat[]>(`${base}/agent-mongo-index-stats`),
    ])
      .then(([m, s, i]) => {
        const filter = <T extends { dbHost?: string }>(arr: T[]) =>
          dbHost
            ? arr.filter((x) => x.dbHost === dbHost || x.dbHost?.includes(dbHost))
            : arr;
        setMetrics(filter(m ?? []));
        setSlowOps(filter(s ?? []));
        setIndexStats(filter(i ?? []));
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [accountId, dbHost]);

  return { metrics, slowOps, indexStats, loading };
}
