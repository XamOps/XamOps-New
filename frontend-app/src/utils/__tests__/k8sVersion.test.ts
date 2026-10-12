import { describe, expect, it } from 'vitest';
import { displayVersion, parseVersionParam, resolveClusterVersion } from '../k8sVersion';

describe('cluster version', () => {
  it.each(['1.36', 'v1.36.4', 'v1.36.4-eks-a737599', '1.29.0+k3s1'])('accepts %s from a link', (raw) => {
    expect(parseVersionParam(raw)).toBe(raw);
  });

  it.each([null, '', 'latest', '1', '1.36<script>', '1.36&forceRefresh=true'])('ignores %j from a link', (raw) => {
    expect(parseVersionParam(raw)).toBeNull();
  });

  it("prefers the agent's gitVersion, then the link's describeCluster version", () => {
    expect(resolveClusterVersion('1.36', 'v1.36.4-eks-a737599')).toBe('v1.36.4-eks-a737599');
    expect(resolveClusterVersion('1.36', null)).toBe('1.36');
  });

  it("never takes the backend's echo of a requested or default version as reported", () => {
    // Without agent data the dashboard repeats the request's version, or "1.28" / "1.33" when none was sent.
    expect(resolveClusterVersion(null, '1.28')).toBeNull();
    expect(resolveClusterVersion(null, '1.33')).toBeNull();
    expect(resolveClusterVersion('1.36', '1.36')).toBe('1.36');
    expect(resolveClusterVersion(null, undefined)).toBeNull();
  });

  it('displays a leading v without the build suffix', () => {
    expect(displayVersion('1.36')).toBe('v1.36');
    expect(displayVersion('v1.36.4-eks-a737599')).toBe('v1.36.4');
    expect(displayVersion('1.29.0+k3s1')).toBe('v1.29.0');
  });
});
