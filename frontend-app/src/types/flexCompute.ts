/**
 * FlexCompute Type Definitions
 *
 * Type definitions for the FlexCompute (CloudiDR) capacity management feature
 */

export interface FlexComputeCapacity {
  id: string;
  name: string;
  accountName: string;
  policyStatus: string;
  instanceState: string;
  instanceType: string;
  quantities: number;
  regionZoneId: string;
  estimatedSavings: number;
  totalSavings: number;
  totalCost: number;
  totalUsageHours: number;
  remainingHours: number;
  startDate: string;
  endDate: string;
  autoRenewal: boolean;
}

export interface FlexComputeKPIs {
  totalSavings: number;
  totalCost: number;
  totalUsageHours: number;
  remainingHours: number;
}

export interface SearchCapacityForm {
  capacityName: string;
  cspAccount: string;
  region: string;
  zoneId: string;
  instanceType: string;
  platform: string;
  tenancy: string;
  quantity: number;
  timeZone: string;
  startDate: string;
  endDate: string;
  autoRenewal: boolean;
}

export interface ConfigureCapacityForm {
  capacityId: string;
  vpcId: string;
  securityGroupId: string;
  sshKeyName: string;
  ebsVolumeSize: number;
  amiImageId: string;
}

export interface CapacityPricing {
  capacityId: string;
  capacityName: string;
  instanceType: string;
  region: string;
  zoneId: string;
  quantity: number;
  platform: string;
  tenancy: string;
  ourPrice: number;
  awsPrice: number;
  savingsPercent: number;
  fees: number;
  total: number;
  startDate: string;
  endDate: string;
  status: string;
}

export type FlexComputeTab = 'dashboard' | 'create';
export type WizardStep = 'search' | 'searching' | 'configure' | 'review' | 'complete';

export interface CloudiDRAuthStatus {
  authenticated: boolean;
  email?: string;
}

export const INITIAL_SEARCH_FORM: SearchCapacityForm = {
  capacityName: '',
  cspAccount: '',
  region: 'us-east-1',
  zoneId: '',
  instanceType: 'c5.large',
  platform: 'Linux/UNIX',
  tenancy: 'Shared',
  quantity: 1,
  timeZone: 'America/Los_Angeles',
  startDate: '',
  endDate: '',
  autoRenewal: false,
};

export const INITIAL_CONFIGURE_FORM: ConfigureCapacityForm = {
  capacityId: '',
  vpcId: '',
  securityGroupId: '',
  sshKeyName: '',
  ebsVolumeSize: 10,
  amiImageId: '',
};

export const AWS_REGIONS = [
  { value: 'us-east-1', label: 'US East (N. Virginia)' },
  { value: 'us-east-2', label: 'US East (Ohio)' },
  { value: 'us-west-1', label: 'US West (N. California)' },
  { value: 'us-west-2', label: 'US West (Oregon)' },
  { value: 'eu-west-1', label: 'EU (Ireland)' },
  { value: 'eu-west-2', label: 'EU (London)' },
  { value: 'eu-central-1', label: 'EU (Frankfurt)' },
  { value: 'ap-south-1', label: 'Asia Pacific (Mumbai)' },
  { value: 'ap-southeast-1', label: 'Asia Pacific (Singapore)' },
  { value: 'ap-southeast-2', label: 'Asia Pacific (Sydney)' },
  { value: 'ap-northeast-1', label: 'Asia Pacific (Tokyo)' },
  { value: 'ca-central-1', label: 'Canada (Central)' },
  { value: 'sa-east-1', label: 'South America (Sao Paulo)' },
];

export const INSTANCE_TYPES = [
  'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c5.4xlarge',
  'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm5.4xlarge',
  'r5.large', 'r5.xlarge', 'r5.2xlarge', 'r5.4xlarge',
  't3.micro', 't3.small', 't3.medium', 't3.large', 't3.xlarge',
  'c6i.large', 'c6i.xlarge', 'c6i.2xlarge',
  'm6i.large', 'm6i.xlarge', 'm6i.2xlarge',
];

export const TIME_ZONES = [
  { value: 'America/Los_Angeles', label: 'Pacific (UTC-8)' },
  { value: 'America/Denver', label: 'Mountain (UTC-7)' },
  { value: 'America/Chicago', label: 'Central (UTC-6)' },
  { value: 'America/New_York', label: 'Eastern (UTC-5)' },
  { value: 'Europe/London', label: 'London (UTC+0)' },
  { value: 'Europe/Berlin', label: 'Berlin (UTC+1)' },
  { value: 'Asia/Kolkata', label: 'India (UTC+5:30)' },
  { value: 'Asia/Tokyo', label: 'Tokyo (UTC+9)' },
  { value: 'Australia/Sydney', label: 'Sydney (UTC+11)' },
];
