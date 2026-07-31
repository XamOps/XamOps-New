/**
 * SOC 2 Program Demo Mock Data  (ROLE_XAMOPS)
 *
 * Backs /api/soc2/* — the Program page and its eleven tabs, plus the framework,
 * control, policy, vendor and risk detail pages. Every one of those screens goes
 * through useSoc2ProgramData, which loads all eleven endpoints in a single
 * Promise.all: one rejection blanks the whole section, so all eleven are stubbed.
 *
 * The overview counters are derived from the lists below rather than hard-coded,
 * so the tiles can never disagree with the tables they link to.
 */

import type {
  Soc2CloudTest, Soc2Control, Soc2Document, Soc2Framework, Soc2Integration,
  Soc2Overview, Soc2Person, Soc2Policy, Soc2Risk, Soc2Task, Soc2Vendor,
} from '../api/soc2Api';

const MS_DAY = 24 * 60 * 60 * 1000;
const daysAgo = (n: number): string => new Date(Date.now() - n * MS_DAY).toISOString();
const daysAhead = (n: number): string => new Date(Date.now() + n * MS_DAY).toISOString();

// ─── /api/soc2/tasks ─────────────────────────────────────────────────────────
// Statuses must be one of done | in_progress | in_review | todo — the evidence
// KPI strip buckets on those exact lowercase values.

export const demoSoc2Tasks: Soc2Task[] = [
  { id: 'tsk-01', title: 'Collect quarterly access review evidence', controlName: 'Logical access provisioning', status: 'done',        assigneeId: 'per-01', assigneeName: 'Akshay Arora',   description: 'Export the IAM user list and record manager approval for each entitlement.', frequency: 'Quarterly' },
  { id: 'tsk-02', title: 'Upload penetration test report',           controlName: 'Vulnerability management', status: 'done',        assigneeId: 'per-02', assigneeName: 'Priya Nair',     description: 'Attach the latest third-party pen-test report and remediation plan.',        frequency: 'Annually' },
  { id: 'tsk-03', title: 'Evidence: backup restoration test',        controlName: 'Backup and recovery',      status: 'done',        assigneeId: 'per-03', assigneeName: 'Rahul Menon',    description: 'Restore a production snapshot into staging and capture the result.',         frequency: 'Semi-annually' },
  { id: 'tsk-04', title: 'Review vendor SOC 2 reports',              controlName: 'Vendor management',        status: 'in_progress', assigneeId: 'per-02', assigneeName: 'Priya Nair',     description: 'Collect current SOC 2 Type II reports for all critical sub-processors.',      frequency: 'Annually' },
  { id: 'tsk-05', title: 'Update the incident response runbook',     controlName: 'Incident response',        status: 'in_progress', assigneeId: 'per-04', assigneeName: 'Sneha Kulkarni', description: 'Refresh escalation contacts and on-call rotation in the IR runbook.',         frequency: 'Annually' },
  { id: 'tsk-06', title: 'Security awareness training completion',   controlName: 'Security awareness',       status: 'in_progress', assigneeId: 'per-05', assigneeName: 'Vikram Shah',    description: 'Chase the remaining employees who have not completed this year’s module.',    frequency: 'Annually' },
  { id: 'tsk-07', title: 'Evidence: encryption at rest attestation', controlName: 'Data encryption',          status: 'in_review',   assigneeId: 'per-01', assigneeName: 'Akshay Arora',   description: 'Screenshot KMS configuration for all production data stores.',               frequency: 'Quarterly' },
  { id: 'tsk-08', title: 'Change management sample walkthrough',     controlName: 'Change management',        status: 'in_review',   assigneeId: 'per-03', assigneeName: 'Rahul Menon',    description: 'Pull 25 sample changes and evidence peer review plus approval.',              frequency: 'Quarterly' },
  { id: 'tsk-09', title: 'Offboarding checklist evidence',           controlName: 'Logical access removal',   status: 'todo',        assigneeId: 'per-04', assigneeName: 'Sneha Kulkarni', description: 'Evidence access revocation within 24 hours for all Q3 leavers.',              frequency: 'Quarterly' },
  { id: 'tsk-10', title: 'Business continuity plan tabletop',        controlName: 'Business continuity',      status: 'todo',        assigneeId: 'per-05', assigneeName: 'Vikram Shah',    description: 'Run the annual tabletop exercise and minute the outcome.',                    frequency: 'Annually' },
  { id: 'tsk-11', title: 'Review firewall rule set',                 controlName: 'Network security',         status: 'todo',        assigneeId: null,     assigneeName: null,             description: 'Confirm every ingress rule has a documented business justification.',         frequency: 'Quarterly' },
  { id: 'tsk-12', title: 'Log retention configuration evidence',     controlName: 'Audit logging',            status: 'todo',        assigneeId: 'per-02', assigneeName: 'Priya Nair',     description: 'Evidence that CloudTrail logs are retained for 365 days.',                    frequency: 'Annually' },
];

const tasksFor = (...ids: string[]): Soc2Task[] =>
  demoSoc2Tasks.filter((t) => ids.includes(t.id));

// ─── /api/soc2/controls ──────────────────────────────────────────────────────

const control = (
  id: string, name: string, category: string, requirementName: string,
  status: Soc2Control['status'], policyName: string | null, policyStatus: string | null,
  compliancePercent: number, policiesPublished: number, policiesTotal: number,
  tasksDone: number, tasksTotal: number, documentsCompleted: number, documentsTotal: number,
  requirements: Array<[string, string]>, tasks: Soc2Task[],
): Soc2Control => ({
  id, name,
  description: `${name} — evidence is collected continuously and reviewed each audit period.`,
  category, requirementName, status, policyName, policyStatus,
  taskCount: tasks.length, compliancePercent,
  policiesPublished, policiesTotal, tasksDone, tasksTotal,
  documentsCompleted, documentsTotal,
  requirements: requirements.map(([identifier, reqName]) => ({ identifier, name: reqName })),
  policies: policyName ? [{ id: `pol-of-${id}`, name: policyName, status: policyStatus }] : [],
  tasks,
});

export const demoSoc2Controls: Soc2Control[] = [
  control('ctl-01', 'Logical access provisioning', 'Access Control', 'CC6.1', 'PASSING',
    'Access Control Policy', 'published', 100, 1, 1, 4, 4, 3, 3,
    [['CC6.1', 'Logical access security software and infrastructure'], ['CC6.2', 'Registration and authorisation of new users']],
    tasksFor('tsk-01')),
  control('ctl-02', 'Logical access removal', 'Access Control', 'CC6.3', 'IN_PROGRESS',
    'Access Control Policy', 'published', 60, 1, 1, 2, 4, 2, 3,
    [['CC6.3', 'Removal of access on termination']],
    tasksFor('tsk-09')),
  control('ctl-03', 'Data encryption', 'Data Protection', 'CC6.7', 'IN_PROGRESS',
    'Data Protection Policy', 'published', 75, 1, 1, 3, 4, 2, 2,
    [['CC6.7', 'Restriction of data transmission'], ['CC6.8', 'Prevention of unauthorised software']],
    tasksFor('tsk-07')),
  control('ctl-04', 'Vulnerability management', 'Risk Management', 'CC7.1', 'PASSING',
    'Vulnerability Management Policy', 'published', 100, 1, 1, 3, 3, 2, 2,
    [['CC7.1', 'Detection of configuration changes and vulnerabilities']],
    tasksFor('tsk-02')),
  control('ctl-05', 'Incident response', 'Operations', 'CC7.3', 'IN_PROGRESS',
    'Incident Response Plan', 'published', 65, 1, 1, 2, 3, 1, 2,
    [['CC7.3', 'Evaluation of security events'], ['CC7.4', 'Response to identified incidents']],
    tasksFor('tsk-05')),
  control('ctl-06', 'Change management', 'Operations', 'CC8.1', 'IN_PROGRESS',
    'Change Management Policy', 'published', 70, 1, 1, 3, 4, 2, 3,
    [['CC8.1', 'Authorisation, design and implementation of changes']],
    tasksFor('tsk-08')),
  control('ctl-07', 'Vendor management', 'Vendor Management', 'CC9.2', 'IN_PROGRESS',
    'Vendor Management Policy', 'draft', 45, 0, 1, 1, 3, 1, 3,
    [['CC9.2', 'Assessment and management of vendor risk']],
    tasksFor('tsk-04')),
  control('ctl-08', 'Security awareness', 'People', 'CC1.4', 'IN_PROGRESS',
    'Information Security Policy', 'published', 80, 1, 1, 4, 5, 2, 2,
    [['CC1.4', 'Commitment to competence']],
    tasksFor('tsk-06')),
  control('ctl-09', 'Backup and recovery', 'Availability', 'A1.2', 'PASSING',
    'Business Continuity Plan', 'published', 100, 1, 1, 2, 2, 2, 2,
    [['A1.2', 'Environmental protections, backup and recovery']],
    tasksFor('tsk-03')),
  control('ctl-10', 'Business continuity', 'Availability', 'A1.3', 'NOT_STARTED',
    'Business Continuity Plan', 'published', 0, 1, 1, 0, 2, 0, 2,
    [['A1.3', 'Testing of recovery plan procedures']],
    tasksFor('tsk-10')),
  control('ctl-11', 'Network security', 'Infrastructure', 'CC6.6', 'IN_PROGRESS',
    'Network Security Policy', 'needs_review', 55, 0, 1, 1, 3, 1, 2,
    [['CC6.6', 'Protection against external threats']],
    tasksFor('tsk-11')),
  control('ctl-12', 'Audit logging', 'Monitoring', 'CC7.2', 'NOT_STARTED',
    'Logging and Monitoring Policy', 'draft', 15, 0, 1, 0, 3, 0, 2,
    [['CC7.2', 'Monitoring of system components']],
    tasksFor('tsk-12')),
  control('ctl-13', 'Risk assessment', 'Risk Management', 'CC3.2', 'PASSING',
    'Risk Management Policy', 'published', 100, 1, 1, 2, 2, 2, 2,
    [['CC3.2', 'Identification and analysis of risks']], []),
  control('ctl-14', 'Board oversight', 'Governance', 'CC1.2', 'PASSING',
    'Information Security Policy', 'published', 100, 1, 1, 1, 1, 1, 1,
    [['CC1.2', 'Board independence and oversight']], []),
];

// ─── /api/soc2/frameworks ────────────────────────────────────────────────────

const completedControls = demoSoc2Controls.filter((c) => c.status === 'PASSING').length;

export const demoSoc2Frameworks: Soc2Framework[] = [
  {
    id: 'fw-soc2',
    name: 'SOC 2 Type II',
    description: 'Trust Services Criteria for security, availability and confidentiality.',
    controlsTotal: demoSoc2Controls.length,
    controlsCompleted: completedControls,
    compliancePercent: Math.round((completedControls * 100) / demoSoc2Controls.length),
  },
  { id: 'fw-iso27001', name: 'ISO 27001:2022', description: 'Information security management system requirements.', controlsTotal: 93, controlsCompleted: 61, compliancePercent: 66 },
  { id: 'fw-gdpr',     name: 'GDPR',           description: 'EU General Data Protection Regulation readiness.',      controlsTotal: 42, controlsCompleted: 30, compliancePercent: 71 },
  { id: 'fw-hipaa',    name: 'HIPAA',          description: 'Security and privacy rules for protected health information.', controlsTotal: 54, controlsCompleted: 22, compliancePercent: 41 },
];

// ─── /api/soc2/policies ──────────────────────────────────────────────────────
// Status is compared lowercase against published | draft | needs_review | archived.

const policy = (
  id: string, name: string, status: string, department: string,
  frequency: string, requiresSignature: boolean, publishedDaysAgo: number | null,
): Soc2Policy => ({
  id, name,
  description: `${name} defining the controls, owners and review cadence for this area.`,
  status,
  content: `# ${name}\n\n## Purpose\n\nThis policy establishes the requirements Xammer follows ` +
    `to protect customer data and meet its SOC 2 commitments.\n\n## Scope\n\nApplies to all ` +
    `employees, contractors and systems that process customer data.\n\n## Review\n\nReviewed ` +
    `${frequency.toLowerCase()} by the ${department} team.`,
  frequency, department, requiresSignature,
  reviewDate: daysAhead(publishedDaysAgo === null ? 30 : 365 - publishedDaysAgo),
  lastPublishedAt: publishedDaysAgo === null ? null : daysAgo(publishedDaysAgo),
  updatedAt: daysAgo(publishedDaysAgo === null ? 4 : Math.max(1, publishedDaysAgo - 5)),
});

export const demoSoc2Policies: Soc2Policy[] = [
  policy('pol-01', 'Information Security Policy',   'published',    'Security',    'Annually', true,  40),
  policy('pol-02', 'Access Control Policy',         'published',    'Security',    'Annually', true,  38),
  policy('pol-03', 'Data Protection Policy',        'published',    'Security',    'Annually', true,  35),
  policy('pol-04', 'Incident Response Plan',        'published',    'Engineering', 'Annually', false, 28),
  policy('pol-05', 'Business Continuity Plan',      'published',    'Operations',  'Annually', false, 24),
  policy('pol-06', 'Change Management Policy',      'published',    'Engineering', 'Annually', false, 21),
  policy('pol-07', 'Vulnerability Management Policy', 'published',  'Security',    'Annually', false, 18),
  policy('pol-08', 'Risk Management Policy',        'published',    'Security',    'Annually', false, 15),
  policy('pol-09', 'Acceptable Use Policy',         'published',    'People',      'Annually', true,  12),
  policy('pol-10', 'Network Security Policy',       'needs_review', 'Engineering', 'Annually', false, 200),
  policy('pol-11', 'Logging and Monitoring Policy', 'draft',        'Engineering', 'Annually', false, null),
  policy('pol-12', 'Vendor Management Policy',      'draft',        'Operations',  'Annually', false, null),
  policy('pol-13', 'Remote Work Policy',            'archived',     'People',      'Annually', false, 420),
];

// ─── /api/soc2/personnel ─────────────────────────────────────────────────────

export const demoSoc2Personnel: Soc2Person[] = [
  { id: 'per-01', name: 'Akshay Arora',    email: 'akshay@xammer.in',  role: 'Admin',    department: 'Security',    jobTitle: 'Head of Platform',      active: true },
  { id: 'per-02', name: 'Priya Nair',      email: 'priya@xammer.in',   role: 'Admin',    department: 'Security',    jobTitle: 'Security Engineer',     active: true },
  { id: 'per-03', name: 'Rahul Menon',     email: 'rahul@xammer.in',   role: 'Member',   department: 'Engineering', jobTitle: 'Staff Engineer',        active: true },
  { id: 'per-04', name: 'Sneha Kulkarni',  email: 'sneha@xammer.in',   role: 'Member',   department: 'Operations',  jobTitle: 'SRE Lead',              active: true },
  { id: 'per-05', name: 'Vikram Shah',     email: 'vikram@xammer.in',  role: 'Member',   department: 'People',      jobTitle: 'People Operations',     active: true },
  { id: 'per-06', name: 'Ananya Iyer',     email: 'ananya@xammer.in',  role: 'Member',   department: 'Engineering', jobTitle: 'Backend Engineer',      active: true },
  { id: 'per-07', name: 'Karthik Reddy',   email: 'karthik@xammer.in', role: 'Member',   department: 'Engineering', jobTitle: 'Frontend Engineer',     active: true },
  { id: 'per-08', name: 'Meera Joshi',     email: 'meera@xammer.in',   role: 'Auditor',  department: 'Finance',     jobTitle: 'Financial Controller',  active: true },
  { id: 'per-09', name: 'Arjun Pillai',    email: 'arjun@xammer.in',   role: 'Member',   department: 'Engineering', jobTitle: 'Data Engineer',         active: false },
  { id: 'per-10', name: 'Divya Rao',       email: 'divya@xammer.in',   role: 'Member',   department: 'Operations',  jobTitle: 'Support Lead',          active: false },
];

// ─── /api/soc2/vendors ───────────────────────────────────────────────────────

const vendor = (
  id: string, name: string, category: string, status: string, website: string,
  subProcessor: boolean, inherentSeverity: string, residualSeverity: string,
  inherentLikelihood: number, inherentImpact: number,
  residualLikelihood: number, residualImpact: number,
): Soc2Vendor => ({
  id, name, category, status,
  description: `${name} — reviewed annually; current SOC 2 Type II report on file.`,
  website, subProcessor,
  inherentSeverity, residualSeverity,
  inherentScore: inherentLikelihood * inherentImpact,
  residualScore: residualLikelihood * residualImpact,
  inherentLikelihood, inherentImpact, residualLikelihood, residualImpact,
});

export const demoSoc2Vendors: Soc2Vendor[] = [
  vendor('ven-01', 'Amazon Web Services', 'Cloud',            'approved',      'https://aws.amazon.com',    true,  'high',     'low',      4, 5, 1, 3),
  vendor('ven-02', 'Google Cloud Platform', 'Cloud',          'approved',      'https://cloud.google.com',  true,  'high',     'low',      4, 5, 1, 3),
  vendor('ven-03', 'Microsoft Azure',     'Cloud',            'approved',      'https://azure.microsoft.com', true, 'high',    'low',      4, 5, 1, 3),
  vendor('ven-04', 'GitHub',              'Development',      'approved',      'https://github.com',        false, 'medium',   'low',      3, 4, 1, 2),
  vendor('ven-05', 'Datadog',             'Security',         'approved',      'https://datadoghq.com',     true,  'medium',   'low',      3, 3, 1, 2),
  vendor('ven-06', 'Slack',               'Development',      'approved',      'https://slack.com',         false, 'medium',   'medium',   3, 3, 2, 2),
  vendor('ven-07', 'Okta',                'Identity & Access', 'approved',     'https://okta.com',          true,  'critical', 'medium',   4, 5, 2, 3),
  vendor('ven-08', 'Stripe',              'Finance',          'approved',      'https://stripe.com',        true,  'high',     'low',      3, 5, 1, 3),
  vendor('ven-09', 'Zoom',                'Development',      'in_review',     'https://zoom.us',           false, 'medium',   'medium',   3, 3, 2, 2),
  vendor('ven-10', 'Notion',              'Development',      'in_review',     'https://notion.so',         false, 'medium',   'medium',   3, 2, 2, 2),
  vendor('ven-11', 'Legacy Analytics Co', 'Development',      'not_approved',  'https://example.com',       false, 'high',     'high',     4, 4, 4, 4),
];

// ─── /api/soc2/risks ─────────────────────────────────────────────────────────

const risk = (
  id: string, title: string, category: string, status: string, severity: string,
  likelihood: number, impact: number, department: string, owner: string, updatedDaysAgo: number,
): Soc2Risk => ({
  id, title,
  description: `${title}. Treatment is tracked against the linked controls and reviewed each quarter.`,
  category, status, severity,
  riskScore: likelihood * impact, likelihood, impact,
  department, owner, updatedAt: daysAgo(updatedDaysAgo),
});

export const demoSoc2Risks: Soc2Risk[] = [
  risk('rsk-01', 'Unauthorised access to production data stores',   'Security',    'open',        'critical', 3, 5, 'Security',    'Akshay Arora',   2),
  risk('rsk-02', 'Extended cloud region outage',                    'Availability', 'mitigated',  'high',     2, 5, 'Operations',  'Sneha Kulkarni', 6),
  risk('rsk-03', 'Sub-processor data breach',                       'Vendor',      'open',        'high',     3, 4, 'Operations',  'Priya Nair',     4),
  risk('rsk-04', 'Insider misuse of privileged credentials',        'Security',    'open',        'high',     2, 5, 'Security',    'Priya Nair',     9),
  risk('rsk-05', 'Unpatched critical CVE in a production image',    'Security',    'mitigated',   'high',     3, 4, 'Engineering', 'Rahul Menon',    1),
  risk('rsk-06', 'Loss of key personnel in the platform team',      'Operational', 'open',        'medium',   3, 3, 'People',      'Vikram Shah',    14),
  risk('rsk-07', 'Non-compliance with data residency commitments',  'Compliance',  'open',        'medium',   2, 4, 'Security',    'Akshay Arora',   11),
  risk('rsk-08', 'Incomplete audit logging on legacy services',     'Security',    'open',        'medium',   3, 3, 'Engineering', 'Rahul Menon',    7),
  risk('rsk-09', 'Phishing leading to credential compromise',       'Security',    'mitigated',   'medium',   3, 3, 'People',      'Vikram Shah',    20),
  risk('rsk-10', 'Backup restoration fails during a real incident', 'Availability', 'closed',     'low',      1, 4, 'Operations',  'Sneha Kulkarni', 30),
];

// ─── /api/soc2/cloud-tests ───────────────────────────────────────────────────
// Grouped by lowercase provider (aws | gcp | azure) on the Cloud Tests tab.

const cloudTest = (
  id: string, title: string, severity: string, status: string,
  provider: string, resource: string,
): Soc2CloudTest => ({ id, title, severity, status, provider, resource });

export const demoSoc2CloudTests: Soc2CloudTest[] = [
  cloudTest('cld-01', 'S3 buckets should block public access',            'critical', 'open',   'aws', 'xammer-prod-assets'),
  cloudTest('cld-02', 'Root account should not have active access keys',  'critical', 'open',   'aws', 'root'),
  cloudTest('cld-03', 'EBS volumes should be encrypted at rest',          'high',     'open',   'aws', 'vol-0f1e2d3c4b5a'),
  cloudTest('cld-04', 'IAM users should have MFA enabled',                'high',     'open',   'aws', 'svc-deploy'),
  cloudTest('cld-05', 'CloudTrail should be enabled in all regions',      'medium',   'passed', 'aws', 'xammer-org-trail'),
  cloudTest('cld-06', 'RDS instances should not be publicly accessible',  'critical', 'open',   'aws', 'prod-orders-db'),
  cloudTest('cld-07', 'GCS buckets should not grant allUsers access',     'high',     'open',   'gcp', 'xammer-gcp-exports'),
  cloudTest('cld-08', 'Cloud SQL should enforce SSL connections',         'high',     'open',   'gcp', 'cloudsql-prod-postgres'),
  cloudTest('cld-09', 'KMS keys should be rotated within 90 days',        'medium',   'open',   'gcp', 'keyRings/prod'),
  cloudTest('cld-10', 'GKE clusters should use shielded nodes',           'medium',   'passed', 'gcp', 'gke-prod-cluster-01'),
  cloudTest('cld-11', 'Storage accounts should block public blob access', 'high',     'open',   'azure', 'xammer-prod-storage'),
  cloudTest('cld-12', 'SQL servers should have auditing enabled',         'high',     'open',   'azure', 'xammer-prod-sql'),
  cloudTest('cld-13', 'NSGs should not allow unrestricted SSH',           'medium',   'open',   'azure', 'nsg-prod-eastus'),
  cloudTest('cld-14', 'Key Vault should have purge protection enabled',   'low',      'passed', 'azure', 'xammer-prod-kv'),
];

// ─── /api/soc2/integrations ──────────────────────────────────────────────────
// `category` selects the card icon; only Cloud / Development / Identity & Access /
// HR & People / Security have one, anything else falls back to a plug icon.

export const demoSoc2Integrations: Soc2Integration[] = [
  { id: 'int-aws',      name: 'Amazon Web Services', category: 'Cloud',             description: 'Continuously test AWS configuration against SOC 2 controls.',        connected: true,  capabilities: 'Config tests, Evidence collection, Inventory sync' },
  { id: 'int-gcp',      name: 'Google Cloud',        category: 'Cloud',             description: 'Pull Security Command Center findings as control evidence.',         connected: true,  capabilities: 'Config tests, Evidence collection' },
  { id: 'int-azure',    name: 'Microsoft Azure',     category: 'Cloud',             description: 'Monitor Azure Security Center for compliance drift.',                connected: true,  capabilities: 'Config tests, Evidence collection' },
  { id: 'int-github',   name: 'GitHub',              category: 'Development',       description: 'Evidence branch protection, code review and CI checks.',             connected: true,  capabilities: 'Change management, Peer review evidence, Repo settings' },
  { id: 'int-okta',     name: 'Okta',                category: 'Identity & Access', description: 'Sync personnel, roles and MFA enrolment state.',                     connected: true,  capabilities: 'Personnel sync, MFA evidence, Access reviews' },
  { id: 'int-datadog',  name: 'Datadog',             category: 'Security',          description: 'Evidence monitoring coverage and alert routing.',                    connected: false, capabilities: 'Monitoring evidence, Alerting' },
  { id: 'int-jira',     name: 'Jira',                category: 'Development',       description: 'Link remediation tickets to controls and tasks.',                    connected: false, capabilities: 'Task sync, Remediation tracking' },
  { id: 'int-bamboohr', name: 'BambooHR',            category: 'HR & People',       description: 'Sync onboarding and offboarding events for access reviews.',         connected: false, capabilities: 'Personnel sync, Onboarding evidence' },
  { id: 'int-slack',    name: 'Slack',               category: 'Development',       description: 'Route compliance alerts and task reminders to channels.',            connected: false, capabilities: 'Notifications' },
];

// ─── /api/soc2/documents ─────────────────────────────────────────────────────
// Category must be exactly Governance | Security | People — the tab renders those
// three buckets and drops anything else.

const doc = (id: string, name: string, category: string, status: string, description: string): Soc2Document =>
  ({ id, name, category, status, description });

export const demoSoc2Documents: Soc2Document[] = [
  doc('doc-01', 'Information Security Program',  'Governance', 'done',         'Master document describing the security programme and its governance.'),
  doc('doc-02', 'Risk Assessment Report',        'Governance', 'done',         'Annual risk assessment covering all in-scope systems.'),
  doc('doc-03', 'Board Meeting Minutes',         'Governance', 'done',         'Evidence of board oversight of the security programme.'),
  doc('doc-04', 'Organisational Chart',          'Governance', 'todo',         'Current reporting structure for in-scope teams.'),
  doc('doc-05', 'System Description',            'Governance', 'todo',         'SOC 2 system description covering infrastructure and data flows.'),
  doc('doc-06', 'Network Architecture Diagram',  'Security',   'done',         'Current production network topology and trust boundaries.'),
  doc('doc-07', 'Data Flow Diagram',             'Security',   'done',         'How customer data moves through the platform.'),
  doc('doc-08', 'Penetration Test Report',       'Security',   'done',         'Latest third-party penetration test and remediation plan.'),
  doc('doc-09', 'Disaster Recovery Test Results', 'Security',  'todo',         'Evidence of the most recent DR restoration test.'),
  doc('doc-10', 'Encryption Standards',          'Security',   'todo',         'Approved algorithms and key management requirements.'),
  doc('doc-11', 'Vulnerability Scan Reports',    'Security',   'optional',     'Monthly scan output retained for the audit period.'),
  doc('doc-12', 'Employee Handbook',             'People',     'done',         'Includes the acceptable use and code of conduct sections.'),
  doc('doc-13', 'Security Awareness Training Records', 'People', 'done',       'Completion records for the current training cycle.'),
  doc('doc-14', 'Background Check Records',      'People',     'todo',         'Evidence of pre-employment screening for all new hires.'),
  doc('doc-15', 'Offboarding Checklists',        'People',     'todo',         'Completed checklists evidencing timely access removal.'),
  doc('doc-16', 'Contractor Agreements',         'People',     'not_relevant', 'Not applicable — no contractors in the audit period.'),
];

// ─── /api/soc2/overview ──────────────────────────────────────────────────────
// Derived from the lists above so the tiles always agree with the tables.

const controlsPassing    = demoSoc2Controls.filter((c) => c.status === 'PASSING').length;
const controlsInProgress = demoSoc2Controls.filter((c) => c.status === 'IN_PROGRESS').length;
const controlsNotStarted = demoSoc2Controls.filter((c) => c.status === 'NOT_STARTED').length;
const isTaskDone = (s: string | null) => ['done', 'completed'].includes((s ?? '').toLowerCase());

export const demoSoc2Overview: Soc2Overview = {
  frameworkName: 'SOC 2 Type II',
  readinessPercent: Math.round((controlsPassing * 100) / demoSoc2Controls.length),
  controlsTotal: demoSoc2Controls.length,
  controlsPassing,
  controlsInProgress,
  controlsNotStarted,
  policiesTotal: demoSoc2Policies.length,
  policiesPublished: demoSoc2Policies.filter((p) => p.status === 'published').length,
  tasksTotal: demoSoc2Tasks.length,
  tasksOpen: demoSoc2Tasks.filter((t) => !isTaskDone(t.status)).length,
  peopleTotal: demoSoc2Personnel.length,
  vendorsTotal: demoSoc2Vendors.length,
  risksTotal: demoSoc2Risks.length,
  cloudFindingsTotal: demoSoc2CloudTests.filter((t) => t.status === 'open').length,
  integrationsConnected: demoSoc2Integrations.filter((i) => i.connected).length,
  trustCenterUrl: 'https://trust.xammer.in',
};
