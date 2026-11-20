import { describe, expect, it } from 'vitest';
import {
  AFFECTED_COLUMNS, CATEGORY, FINDING_COLUMNS, FRAMEWORK, NO_FRAMEWORK, REGION, SEVERITY, affectedCsv,
  affectedFileName, affectedRegionOptions, dimensionLabel, filterAffected, filterMisconfigurations,
  findingFilterOptions, findingsCsv, findingsFileName, findingsOnResource, frameworkLabel, groupFindings,
  groupMisconfigurations, groupSizes, labelMemoryOf, parseFindingText, resourceKey, severityCode, severityName,
  severityRank, summarize,
} from '../findingsModel';
import {
  FINDINGS, FLOW_LOGS, LIVE_BUCKET, MFA_ALICE, MFA_BOB, PUBLIC_BUCKET, ROOT_KEYS, SEGREGATION, SSH_A, SSH_B, TITLES,
  UNUSED_ROLE, finding,
} from './fixtures';

const ROWS = groupFindings(FINDINGS);
const row = (title: string) => {
  const found = ROWS.find(r => r.title === title);
  if (!found) throw new Error(`no row "${title}"`);
  return found;
};
const include = (values: string[]) => ({ mode: 'include' as const, values });

describe('severity', () => {
  it('reads title case and upper case alike, and ranks Critical > High > Medium > Low > other', () => {
    expect(['High', ' critical ', 'LOW', '', null, 'Informational'].map(severityCode))
      .toEqual(['HIGH', 'CRITICAL', 'LOW', null, null, 'INFORMATIONAL']);
    expect(['LOW', null, 'CRITICAL', 'MEDIUM', 'HIGH', 'INFORMATIONAL'].map(severityRank)).toEqual([1, 0, 4, 2, 3, 0]);
    expect(severityName('CRITICAL')).toBe('Critical');
  });
});

describe('parseFindingText', () => {
  it('keeps a scan check sentence whole', () => {
    expect(parseFindingText('VPC does not have Flow Logs enabled.'))
      .toEqual({ title: 'VPC does not have Flow Logs enabled.', description: null, remediation: null });
  });

  it('splits a live finding into title, description and remediation', () => {
    expect(parseFindingText(LIVE_BUCKET.description)).toEqual({
      title: 'S3 buckets should block public access',
      description: 'This control checks whether Block Public Access is on.',
      remediation: 'Turn on Block Public Access for the bucket.',
    });
    expect(parseFindingText('Port probe detected Remediation: Block the source.'))
      .toEqual({ title: 'Port probe detected', description: null, remediation: 'Block the source.' });
  });

  it('gives an empty title for a blank description', () => {
    expect(parseFindingText(null)).toEqual({ title: '', description: null, remediation: null });
  });
});

describe('groupFindings', () => {
  it('makes one row per check, highest severity first, then most affected, then title', () => {
    expect(ROWS.map(r => r.title)).toEqual(TITLES);
    expect(ROWS.map(r => r.findings.length)).toEqual([2, 1, 1, 2, 1, 1, 1, 1]);
  });

  it('collects the affected resources and their regions, A to Z', () => {
    const ssh = row(TITLES[0]);
    expect(ssh.findings).toEqual([SSH_A, SSH_B]);
    expect(ssh.regions).toEqual(['ap-south-1', 'us-east-1']);
    expect(row(TITLES[3]).findings).toEqual([MFA_ALICE, MFA_BOB]);
  });

  it('keeps two checks apart when they share a control but not a severity or text', () => {
    expect(ROWS.filter(r => r.controlId === 'CC6.1').map(r => r.findings)).toEqual([[ROOT_KEYS], [SEGREGATION]]);
  });

  it('keeps a name used in two regions as two resources', () => {
    const east = finding({ resourceId: 'database-1', region: 'us-east-1', category: 'RDS', controlId: 'CC6.7' });
    const mumbai = { ...east, region: 'ap-south-1' };
    const rows = groupFindings([east, mumbai, { ...east }]);
    expect(rows).toHaveLength(1);
    expect(rows[0].findings).toEqual([east, mumbai]);
    expect(summarize(rows).affectedResources).toBe(2);
    expect(resourceKey(east)).not.toBe(resourceKey(mumbai));
    expect(findingsOnResource([east, mumbai, MFA_ALICE], mumbai)).toEqual([mumbai]);
  });

  it('merges title case and upper case severities and counts a resource reported twice once', () => {
    const rows = groupFindings([MFA_ALICE, { ...MFA_ALICE, severity: 'HIGH' }, MFA_BOB]);
    expect(rows).toHaveLength(1);
    expect(rows[0].findings).toEqual([MFA_ALICE, MFA_BOB]);
    expect(rows[0].severity).toBe('HIGH');
    expect(rows[0].severityLabel).toBe('High');
  });

  it('carries a live finding\'s parsed text', () => {
    const live = row('S3 buckets should block public access');
    expect(live.description).toBe('This control checks whether Block Public Access is on.');
    expect(live.remediation).toBe('Turn on Block Public Access for the bucket.');
    expect(live.category).toBe('AwsS3Bucket');
  });

  it('names the framework with its control', () => {
    expect(frameworkLabel(row(TITLES[3]))).toBe('CIS AWS Foundations 1.2');
    expect(frameworkLabel({ framework: null, controlId: 'IAM-9' })).toBe('IAM-9');
    expect(frameworkLabel({ framework: null, controlId: null })).toBeNull();
  });
});

describe('summarize', () => {
  it('counts distinct resources, rows, findings and rows per severity', () => {
    expect(summarize(ROWS)).toEqual({
      affectedResources: 10,
      misconfigurations: 8,
      findings: 10,
      bySeverity: { CRITICAL: 3, HIGH: 2, MEDIUM: 2, LOW: 1 },
    });
  });

  it('counts a resource that fails two checks once', () => {
    const rows = groupFindings([MFA_ALICE, finding({ resourceId: 'alice', controlId: '1.4', description: 'Access key unused.' })]);
    expect(summarize(rows)).toMatchObject({ affectedResources: 1, misconfigurations: 2, findings: 2 });
  });
});

describe('filter options and filtering', () => {
  const options = findingFilterOptions(ROWS);
  const dimension = (key: string) => options.dimensions.find(d => d.key === key)!.options;

  it('lists Severity highest first and the More-filters dimensions A to Z, counting rows', () => {
    expect([SEVERITY, CATEGORY, FRAMEWORK, REGION].map(dimensionLabel)).toEqual(['Severity', 'Category', 'Framework', 'Region']);
    expect(dimension(SEVERITY)).toEqual([
      { value: 'CRITICAL', label: 'Critical', count: 3 },
      { value: 'HIGH', label: 'High', count: 2 },
      { value: 'MEDIUM', label: 'Medium', count: 2 },
      { value: 'LOW', label: 'Low', count: 1 },
    ]);
    expect(dimension(CATEGORY).map(o => `${o.label} ${o.count}`)).toEqual(['AwsS3Bucket 1', 'IAM 4', 'S3 1', 'VPC 2']);
    expect(dimension(FRAMEWORK).map(o => `${o.label} ${o.count}`))
      .toEqual(['CIS AWS Foundations 4', 'Custom Best Practice 1', 'Security Hub 1', 'SOC2 2']);
    expect(dimension(REGION).map(o => `${o.label} ${o.count}`))
      .toEqual(['ap-south-1 1', 'eu-west-1 1', 'Global 4', 'us-east-1 3']);
    expect(labelMemoryOf(options).get(SEVERITY)?.get('CRITICAL')).toBe('Critical');
  });

  it('searches titles, control ids and resource ids, ignoring case', () => {
    expect(filterMisconfigurations(ROWS, '  flow LOGS ', {}).map(r => r.title)).toEqual([FLOW_LOGS.description]);
    expect(filterMisconfigurations(ROWS, 'cc6.1', {})).toHaveLength(2);
    expect(filterMisconfigurations(ROWS, 'SG-0BBB', {}).map(r => r.title)).toEqual([TITLES[0]]);
  });

  it('combines dimensions with AND and values with OR', () => {
    const titles = (filters: Parameters<typeof filterMisconfigurations>[2]) =>
      filterMisconfigurations(ROWS, '', filters).map(r => r.title);
    expect(titles({ [SEVERITY]: include(['CRITICAL', 'LOW']) })).toEqual([TITLES[0], TITLES[1], TITLES[2], TITLES[7]]);
    expect(titles({ [SEVERITY]: include(['CRITICAL']), [FRAMEWORK]: include(['SOC2']) })).toEqual([TITLES[2]]);
    expect(titles({ [REGION]: include(['ap-south-1']) })).toEqual([TITLES[0]]);
    expect(titles({ [CATEGORY]: include(['VPC']), [REGION]: include(['us-east-1']) })).toEqual([TITLES[0], TITLES[6]]);
    expect(titles({ [SEVERITY]: include([]) })).toEqual(TITLES);
  });
});

describe('groupMisconfigurations', () => {
  it('groups by severity, highest first', () => {
    const items = groupMisconfigurations(ROWS, 'severity');
    expect([...groupSizes(items)]).toEqual([['CRITICAL', 3], ['HIGH', 2], ['MEDIUM', 2], ['LOW', 1]]);
    expect(items[0].groupLabel).toBe('Critical');
  });

  it('groups by framework A to Z, rows without one last', () => {
    const rows = groupFindings([MFA_ALICE, ROOT_KEYS, finding({ resourceId: 'x', complianceFramework: undefined })]);
    const items = groupMisconfigurations(rows, 'framework');
    expect(items.map(i => i.groupKey)).toEqual(['CIS AWS Foundations', 'SOC2', NO_FRAMEWORK]);
  });

  it('leaves the rows ungrouped with none', () => {
    expect(groupMisconfigurations(ROWS, 'none').every(i => i.groupKey === null)).toBe(true);
  });
});

describe('CSV', () => {
  it('writes the given columns of the given rows', () => {
    const csv = findingsCsv([row(TITLES[0]), row(TITLES[7])], FINDING_COLUMNS);
    expect(csv.split('\r\n')).toEqual([
      'Misconfiguration,Severity,Affected Resources,Category,Regions,Frameworks',
      'Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 22,Critical,2,VPC,"ap-south-1, us-east-1",CIS AWS Foundations 4.1',
      'Role has not been used in over 90 days.,Low,1,IAM,Global,Custom Best Practice IAM-002',
    ]);
    expect(findingsCsv([row(TITLES[7])], FINDING_COLUMNS.slice(0, 2)).split('\r\n'))
      .toEqual(['Misconfiguration,Severity', 'Role has not been used in over 90 days.,Low']);
  });

  it('exports affected resources without the Actions column', () => {
    expect(affectedCsv([SSH_A, SSH_B], AFFECTED_COLUMNS).split('\r\n'))
      .toEqual(['Affected Resource,Region', 'sg-0aaa1111,us-east-1', 'sg-0bbb2222,ap-south-1']);
  });

  it('dates the file names', () => {
    const now = new Date(2026, 10, 20);
    expect(findingsFileName(now)).toBe('security-misconfigurations-2026-11-20.csv');
    expect(affectedFileName(row(TITLES[0]), now))
      .toBe('security-misconfiguration-cis-aws-foundations-4.1-affected-resources-2026-11-20.csv');
  });
});

describe('Affected Resources filter', () => {
  const affected = [SSH_A, SSH_B, finding({ resourceId: 'sg-0ccc3333', region: 'us-east-1' }), finding({ resourceId: 'acct', region: '' })];

  it('lists the regions A to Z with their resource counts', () => {
    expect(affectedRegionOptions(affected)).toEqual([
      { value: 'ap-south-1', label: 'ap-south-1', count: 1 },
      { value: 'us-east-1', label: 'us-east-1', count: 2 },
    ]);
  });

  it('searches resource ids and filters by region', () => {
    expect(filterAffected(affected, 'BBB', {})).toEqual([SSH_B]);
    expect(filterAffected(affected, '', { [REGION]: include(['us-east-1']) }).map(f => f.resourceId))
      .toEqual(['sg-0aaa1111', 'sg-0ccc3333']);
    expect(filterAffected([PUBLIC_BUCKET, UNUSED_ROLE], 'missing', {})).toEqual([]);
  });
});
