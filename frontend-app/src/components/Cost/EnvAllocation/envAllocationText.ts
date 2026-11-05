import type {
  AllocationKind,
  AllocationType,
  MatchField,
  MatchOperator,
  Rule,
} from '../../../api/envAllocationApi';

/** Shared wording and small helpers for the environment allocation tab and its drawer. */

export const FIELD_LABELS: Record<MatchField, string> = {
  ANY: 'Everything else',
  SERVICE: 'Service',
  USAGE_TYPE: 'Usage type',
  REGION: 'Region',
  ACCOUNT: 'Account',
  CHARGE_TYPE: 'Charge type',
  RESOURCE_ID: 'Resource id',
  // Not offered. Only here so a rule saved before AWS tags were removed still shows a name.
  TAG: 'AWS tag (removed)',
  LABEL: 'XamOps label',
};

/** Where each field's values come from, shown under the field picker. */
export const FIELD_HINTS: Record<MatchField, string> = {
  ANY: 'Matches every line no earlier rule took. Put it last.',
  SERVICE: 'The product name on the bill, e.g. "Amazon Elastic Compute Cloud" or "Claude Sonnet 4.6 (Amazon Bedrock Edition)".',
  USAGE_TYPE: 'e.g. "USE1-SpotUsage:m5.large" or "USE1-Claude4.6Sonnet-input-tokens". Bedrock models are named here.',
  REGION: 'e.g. "us-east-1". Charges with no region read "Global".',
  ACCOUNT: 'The 12-digit AWS account the usage belongs to.',
  CHARGE_TYPE: 'e.g. Usage, Tax, Credit, Refund, SavingsPlanCoveredUsage.',
  RESOURCE_ID: 'An instance id, bucket name or ARN. EKS log groups contain "/aws/eks/".',
  TAG: 'AWS tags are no longer used here. Pick another field or delete this rule.',
  LABEL: 'Labels you put on resources in XamOps. They apply to past months too.',
};

export const OPERATOR_LABELS: Record<MatchOperator, string> = {
  EQUALS: 'equals',
  CONTAINS: 'contains',
  STARTS_WITH: 'starts with',
  EXISTS: 'has any value',
};

export const ALLOCATION_LABELS: Record<AllocationType, string> = {
  FIXED: 'Fixed percentages',
  FROM_VALUE: 'The environment the value names',
  PROPORTIONAL: 'In proportion to everything else',
};

export const KIND_LABELS: Record<AllocationKind, string> = {
  TAGGED: 'Label',
  RULE: 'Rule',
  SPLIT: 'Split',
  PROPORTIONAL: 'Proportional',
  UNALLOCATED: 'Unallocated',
};

export const describeMatch = (rule: Rule): string => {
  if (rule.field === 'ANY') return 'Every remaining line';
  const subject = FIELD_LABELS[rule.field];
  if (rule.operator === 'EXISTS') {
    return rule.field === 'LABEL' ? 'Resource has any XamOps label' : `${subject} has any value`;
  }
  return `${subject} ${OPERATOR_LABELS[rule.operator]} "${rule.value ?? ''}"`;
};

export const describeAllocation = (rule: Rule, envName: (id: number) => string): string => {
  if (rule.allocation === 'FROM_VALUE') return 'the environment the value names';
  if (rule.allocation === 'PROPORTIONAL') return 'in proportion to everything else';
  return rule.splits
    .map((s) => `${trimPercent(s.percent)}% ${s.environmentName || envName(s.environmentId)}`)
    .join(', ');
};

export const trimPercent = (p: number | null | undefined): string => {
  if (p == null) return '';
  return Number(p.toFixed(4)).toString();
};

export interface MonthOption {
  value: string;
  label: string;
}

const key = (d: Date): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;

/** The last 13 months, newest first — the open month and the twelve before it. */
export const monthOptions = (): MonthOption[] => {
  const out: MonthOption[] = [];
  const d = new Date();
  d.setDate(1);
  for (let i = 0; i < 13; i++) {
    out.push({ value: key(d), label: d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }) });
    d.setMonth(d.getMonth() - 1);
  }
  return out;
};

export const currentMonth = (): string => key(new Date());

export const monthLabel = (value: string): string => {
  const m = /^(\d{4})-(\d{2})$/.exec(value);
  if (!m) return value;
  return new Date(Number(m[1]), Number(m[2]) - 1, 1).toLocaleDateString('en-US', {
    month: 'long',
    year: 'numeric',
  });
};

export const when = (iso: string | null | undefined): string => {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
};

/** A blank rule of the given shape, for the "add rule" menu. */
export const newRule = (template: 'usage' | 'service' | 'label' | 'rest'): Rule => {
  switch (template) {
    case 'label':
      return {
        id: null,
        name: 'Environment label',
        field: 'LABEL',
        operator: 'EXISTS',
        value: null,
        tagKey: null,
        allocation: 'FROM_VALUE',
        splits: [],
      };
    case 'service':
      return {
        id: null,
        name: 'New service rule',
        field: 'SERVICE',
        operator: 'CONTAINS',
        value: '',
        tagKey: null,
        allocation: 'FIXED',
        splits: [],
      };
    case 'rest':
      return {
        id: null,
        name: 'Everything else',
        field: 'ANY',
        operator: 'EXISTS',
        value: null,
        tagKey: null,
        allocation: 'FIXED',
        splits: [],
      };
    case 'usage':
    default:
      return {
        id: null,
        name: 'New usage type rule',
        field: 'USAGE_TYPE',
        operator: 'CONTAINS',
        value: '',
        tagKey: null,
        allocation: 'FIXED',
        splits: [],
      };
  }
};

/** What is wrong with a rule before it is sent, or null. The server checks again. */
export const ruleProblem = (rule: Rule): string | null => {
  if (!rule.name.trim()) return 'Give the rule a name.';
  if (rule.field === 'TAG') return 'AWS tag rules are no longer supported. Pick another field or delete this rule.';
  if (rule.field !== 'ANY' && rule.operator !== 'EXISTS' && !rule.value?.trim()) {
    return 'Enter a value to compare with.';
  }
  if (rule.allocation === 'FIXED') {
    if (rule.splits.length === 0) return 'Add at least one environment.';
    if (rule.splits.some((s) => !(s.percent > 0))) return 'Every share must be above 0%.';
    const sum = rule.splits.reduce((a, s) => a + s.percent, 0);
    if (Math.abs(sum - 100) > 0.0001) return `Shares add up to ${trimPercent(sum)}%, not 100%.`;
  }
  return null;
};
