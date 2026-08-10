import { describe, expect, it } from 'vitest';
import { PLACEHOLDERS, substituteVars, type GuideVars } from '../AzureCloudK8sHelpModal';

/**
 * The AKS guide's command blocks are copied straight into live clusters, so substitution
 * has to be exact. In particular a half-filled form must never produce a command that
 * looks runnable — an empty cluster name in a ConfigMap silently registers the wrong
 * cluster and makes two clusters collide on one cluster_config_id.
 */
describe('substituteVars', () => {
  const filled: GuideVars = {
    clusterName: 'xamops-aks-test',
    resourceGroup: 'xamops-aks-test-rg',
    subscriptionId: '904f8213-cabb-4591-b6e4-2cb80ac93158',
  };

  const empty: GuideVars = { clusterName: '', resourceGroup: '', subscriptionId: '' };

  it('replaces every placeholder in a multi-line command', () => {
    const code = [
      'az aks get-credentials \\',
      `  --resource-group ${PLACEHOLDERS.resourceGroup} \\`,
      `  --name ${PLACEHOLDERS.clusterName}`,
    ].join('\n');

    expect(substituteVars(code, filled)).toBe(
      [
        'az aks get-credentials \\',
        '  --resource-group xamops-aks-test-rg \\',
        '  --name xamops-aks-test',
      ].join('\n'),
    );
  });

  it('replaces repeated occurrences of the same placeholder', () => {
    const code = `${PLACEHOLDERS.clusterName} and again ${PLACEHOLDERS.clusterName}`;
    expect(substituteVars(code, filled)).toBe('xamops-aks-test and again xamops-aks-test');
  });

  it('leaves blank values as literal tokens rather than empty strings', () => {
    const code = `CLUSTER_NAME: "${PLACEHOLDERS.clusterName}"`;
    expect(substituteVars(code, empty)).toBe('CLUSTER_NAME: "<CLUSTER_NAME>"');
    expect(substituteVars(code, empty)).not.toContain('""');
  });

  it('substitutes only the filled fields when the form is partially complete', () => {
    const partial: GuideVars = { ...empty, clusterName: 'xamops-cluster' };
    const code = `${PLACEHOLDERS.clusterName} / ${PLACEHOLDERS.resourceGroup}`;
    expect(substituteVars(code, partial)).toBe('xamops-cluster / <RESOURCE_GROUP>');
  });

  it('trims surrounding whitespace from pasted values', () => {
    const padded: GuideVars = { ...empty, clusterName: '  xamops-aks-test  ' };
    expect(substituteVars(PLACEHOLDERS.clusterName, padded)).toBe('xamops-aks-test');
  });

  it('treats a whitespace-only value as unset', () => {
    const blank: GuideVars = { ...empty, resourceGroup: '   ' };
    expect(substituteVars(PLACEHOLDERS.resourceGroup, blank)).toBe('<RESOURCE_GROUP>');
  });

  it('does not confuse the subscription placeholder with the cluster one', () => {
    const code = `${PLACEHOLDERS.clusterName}|${PLACEHOLDERS.subscriptionId}`;
    expect(substituteVars(code, filled))
      .toBe('xamops-aks-test|904f8213-cabb-4591-b6e4-2cb80ac93158');
  });

  it('leaves values containing regex-special characters intact', () => {
    // Substitution is split/join, not regex, so a name with dots or dashes is safe.
    const tricky: GuideVars = { ...empty, clusterName: 'a.b-c$d' };
    expect(substituteVars(`name: ${PLACEHOLDERS.clusterName}`, tricky)).toBe('name: a.b-c$d');
  });

  it('returns the input unchanged when it has no placeholders', () => {
    const code = 'kubectl get pods -n xamops-system';
    expect(substituteVars(code, filled)).toBe(code);
  });
});
