/**
 * Account Manager TypeScript Types
 *
 * Types for the Account Manager page - managing cloud accounts, GitHub repos, and Jenkins servers
 */

import { CloudProvider } from './account';

export interface CloudAccount {
  id: string;
  dbId: number;
  name: string;
  provider: CloudProvider;
  status: 'CONNECTED' | 'PENDING' | 'FAILED';
  access?: string;
  externalId?: string;
  azureSubscriptionId?: string;
  billingExportTable?: string;
  cspManaged?: boolean;
  billingDataAvailable?: boolean | null;
}

export interface GitHubConfig {
  id: number;
  owner: string;
  repo: string;
}

export interface JenkinsConfig {
  id: number;
  jenkinsUrl: string;
  username: string;
}

export interface VerificationDetails {
  awsAccountId: string;
  roleName: string;
  externalId?: string;
}

export interface AccountManagerState {
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  jenkinsConfigs: JenkinsConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
  isJenkinsLoading: boolean;
}
