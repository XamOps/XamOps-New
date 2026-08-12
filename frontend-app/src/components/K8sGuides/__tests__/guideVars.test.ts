import { describe, expect, it } from 'vitest';
import { allTokensFilled, substituteTokens, type GuideVarSpec } from '../guideVars';
import { EKS_PLACEHOLDERS } from '../../EksDetails/EksIntegrationModal';

/**
 * These guard the failure mode that motivated the variable bar: substitution that looks like
 * it worked but produced a subtly wrong command. A cluster name that differs between the
 * Kubescape `--set clusterName` and the agent ConfigMap does not error — it files the data
 * under a different cluster, which is far harder to notice than a broken command.
 */
describe('substituteTokens', () => {
  const filled = {
    [EKS_PLACEHOLDERS.clusterName]: 'my-prod-cluster',
    [EKS_PLACEHOLDERS.region]: 'ap-south-1',
    [EKS_PLACEHOLDERS.accountId]: '123456789012',
    [EKS_PLACEHOLDERS.nodegroupRole]: 'eksctl-ng-NodeInstanceRole',
  };

  it('substitutes every token in a multi-line command', () => {
    const code = [
      'aws eks create-addon \\',
      `  --region ${EKS_PLACEHOLDERS.region} \\`,
      `  --cluster-name ${EKS_PLACEHOLDERS.clusterName}`,
    ].join('\n');

    expect(substituteTokens(code, filled)).toBe(
      ['aws eks create-addon \\', '  --region ap-south-1 \\', '  --cluster-name my-prod-cluster'].join('\n'),
    );
  });

  it('replaces repeated occurrences of the same token', () => {
    const code = `${EKS_PLACEHOLDERS.clusterName} then ${EKS_PLACEHOLDERS.clusterName}`;
    expect(substituteTokens(code, filled)).toBe('my-prod-cluster then my-prod-cluster');
  });

  it('leaves blank values as the literal token rather than an empty string', () => {
    const code = `CLUSTER_NAME: "${EKS_PLACEHOLDERS.clusterName}"`;
    const out = substituteTokens(code, { [EKS_PLACEHOLDERS.clusterName]: '' });
    expect(out).toBe('CLUSTER_NAME: "<CLUSTER_NAME>"');
    expect(out).not.toContain('""');
  });

  it('treats a whitespace-only value as unset', () => {
    expect(substituteTokens(EKS_PLACEHOLDERS.region, { [EKS_PLACEHOLDERS.region]: '   ' })).toBe('<REGION>');
  });

  it('trims pasted values', () => {
    expect(
      substituteTokens(EKS_PLACEHOLDERS.accountId, { [EKS_PLACEHOLDERS.accountId]: ' 123456789012 ' }),
    ).toBe('123456789012');
  });

  it('substitutes only the filled tokens when the form is partially complete', () => {
    const code = `${EKS_PLACEHOLDERS.clusterName} / ${EKS_PLACEHOLDERS.region}`;
    expect(substituteTokens(code, { [EKS_PLACEHOLDERS.clusterName]: 'c1' })).toBe('c1 / <REGION>');
  });

  it('treats values as literal text, not regex replacements', () => {
    // A `$&` in a value would duplicate the match if this used String.replace.
    expect(substituteTokens(EKS_PLACEHOLDERS.clusterName, { [EKS_PLACEHOLDERS.clusterName]: 'a.b-c$&d' }))
      .toBe('a.b-c$&d');
  });

  it('does not let one token bleed into another', () => {
    const code = `${EKS_PLACEHOLDERS.clusterName}|${EKS_PLACEHOLDERS.accountId}`;
    expect(substituteTokens(code, filled)).toBe('my-prod-cluster|123456789012');
  });

  it('returns the input unchanged when it contains no tokens', () => {
    const code = 'kubectl get pods -n xamops-system';
    expect(substituteTokens(code, filled)).toBe(code);
  });

  it('substitutes the environment-derived server url and api key', () => {
    const code = 'XAMOPS_SERVER_URL: "<XAMOPS_SERVER_URL>"\n  XAMOPS_API_KEY: "<XAMOPS_API_KEY>"';
    const out = substituteTokens(code, {
      [EKS_PLACEHOLDERS.serverUrl]: 'http://localhost:8080',
      [EKS_PLACEHOLDERS.apiKey]: 'local-key',
    });
    expect(out).toContain('XAMOPS_SERVER_URL: "http://localhost:8080"');
    expect(out).toContain('XAMOPS_API_KEY: "local-key"');
  });
});

describe('allTokensFilled', () => {
  const specs: GuideVarSpec[] = [
    { token: '<A>', label: 'A' },
    { token: '<B>', label: 'B' },
  ];

  it('is false while any token is blank or whitespace', () => {
    expect(allTokensFilled(specs, {})).toBe(false);
    expect(allTokensFilled(specs, { '<A>': 'x' })).toBe(false);
    expect(allTokensFilled(specs, { '<A>': 'x', '<B>': '  ' })).toBe(false);
  });

  it('is true once every token has a value', () => {
    expect(allTokensFilled(specs, { '<A>': 'x', '<B>': 'y' })).toBe(true);
  });
});
