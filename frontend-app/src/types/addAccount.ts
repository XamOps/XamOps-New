/**
 * AddAccount TypeScript Types
 *
 * Types for the Add Account page - managing cloud provider connections
 */

export type Provider = 'aws' | 'gcp' | 'azure' | 'jenkins' | 'github';

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

/**
 * Whether we're still listening for the CloudFormation stack to report itself connected.
 *
 * This never gates the manual form — the customer can fill it in at any point. It only
 * drives the status banner above it.
 *
 * - `idle`     — not listening (EventBridge disabled on this environment, or not launched yet)
 * - `listening`— stack is within its expected build time
 * - `slow`     — past that, still listening; a stack can legitimately run long
 * - `stopped`  — gave up polling; manual entry is the only route left
 */
export type AutoConnectStatus = 'idle' | 'listening' | 'slow' | 'stopped';

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
