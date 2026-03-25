/**
 * AddAccount TypeScript Types
 *
 * Types for the Add Account page - managing cloud provider connections
 */

export type Provider = 'aws' | 'gcp' | 'azure' | 'jenkins';

export type AccessType = 'read-only' | 'read-write';

export interface VerificationDetails {
  awsAccountId: string;
  roleName: string;
  externalId: string;
}

export interface AzureDetails {
  accountName: string;
  azureCredentialsJson: string;
}

export interface AzureCredentials {
  tenantId: string;
  subscriptionId: string;
  clientId: string;
  clientSecret: string;
  accountName: string;
}

export interface JenkinsDetails {
  url: string;
  username: string;
  token: string;
}

export interface GitHubDetails {
  owner: string;
  repo: string;
  pat: string;
}

export interface GcpAccountDetails {
  accountName: string;
  projectId: string;
  serviceAccountKey: string;
  billingTable: string;
}

export interface GcpAccountState {
  accountName: string;
  projectId: string;
  serviceAccountKey: string;
  billingTable: string;
  isLoading: boolean;
  copyButtonText: string;
  showScript: boolean;
  showGuide: boolean;
  commandText: string;
  fullScript: string;
  scriptUrl: string;
}

export interface StackUrlResponse {
  url: string;
  externalId: string;
  eventBridgeEnabled: string; // "true" or "false" from backend
}

export interface AddAccountState {
  provider: Provider;
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  isPolicyModalOpen: boolean;
  verificationError: string;
  verificationDetails: VerificationDetails;
  azureDetails: AzureDetails;
  jenkinsDetails: JenkinsDetails;
}
