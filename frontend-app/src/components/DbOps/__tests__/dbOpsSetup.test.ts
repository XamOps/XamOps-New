import { describe, it, expect } from 'vitest';
import {
  POSTGRES_SETUP_SQL,
  AGENT_IMAGE,
  buildDsn,
  buildAgentRunCmd,
  type DsnEntry,
} from '../DbInstanceDetailDrawer';

describe('DbOps setup — SEC-7 Postgres grant', () => {
  it('grants stats-only access (pg_read_all_stats), not blanket table SELECT', () => {
    expect(POSTGRES_SETUP_SQL).toContain('pg_read_all_stats');
    expect(POSTGRES_SETUP_SQL).not.toContain('SELECT ON ALL TABLES');
  });
});

describe('DbOps setup — SEC-12 install command', () => {
  const dsn: DsnEntry = {
    engine: 'postgres',
    host: 'db.example.com',
    port: '5432',
    dbName: 'app',
    user: 'xamops_reader',
    pass: 'super-secret-password',
    sslMode: 'require',
  };
  const cmd = buildAgentRunCmd({
    serverUrl: 'https://live.xamops.com',
    apiKey: 'xak_demo',
    clusterName: 'prod-db',
    cloudAccountId: '111111111111',
    queryLimit: 200,
    dsns: [dsn],
  });

  it('pins the agent image tag to v2.1.0', () => {
    expect(AGENT_IMAGE).toBe('xamops/agent:v2.1.0');
    expect(cmd).toContain(':v2.1.0');
    expect(cmd).not.toContain(':latest');
  });

  it('passes the DSN via an env-file, not an inline -e DB_DSN_ flag', () => {
    expect(cmd).toContain('--env-file');
    expect(cmd).toContain('xamops-agent.env');
    expect(cmd).not.toContain('-e DB_DSN_');
  });

  it('never embeds the real password (masked as ***)', () => {
    expect(cmd).not.toContain('super-secret-password');
    expect(cmd).toContain('***');
    expect(buildDsn(dsn)).toContain('***');
    expect(buildDsn(dsn)).not.toContain('super-secret-password');
  });
});
