/**
 * TypeScript types for CICD Pipelines functionality
 */

// GitHub specific types
export interface GitHubRepository {
  full_name: string;
}

export type GitHubStatus = 'success' | 'failure' | 'in_progress' | 'cancelled' | 'completed';

export interface GitHubRun {
  id: number;
  name: string;
  status: string;
  conclusion: string | null;
  displayStatus: string;
  html_url: string;
  updated_at: string;
  run_number: number;
  workflow_id: number;
  repository: GitHubRepository | null;
}

export interface GitHubStage {
  name: string;
  status: 'success' | 'failure' | 'running' | 'skipped' | 'pending';
  duration: string;
}

// Jenkins specific types
export interface JenkinsLastBuild {
  number: number;
  url: string;
  timestamp: number;
}

export type JenkinsResult = 'SUCCESS' | 'FAILURE' | 'UNSTABLE' | 'ABORTED' | 'BUILDING';

export interface JenkinsJob {
  name: string;
  url: string;
  displayStatus: string;
  color: string;
  lastBuild: JenkinsLastBuild | null;
}

export interface JenkinsBuild {
  number: number;
  url: string;
  timestamp: number;
  result: JenkinsResult;
  displayStatus: string;
  run_number?: number;
}

export interface JenkinsStage {
  name: string;
  status: 'success' | 'failure' | 'running' | 'skipped' | 'pending';
  duration: string;
}

// Union types for handling both GitHub and Jenkins
export type CICDRun = GitHubRun | JenkinsJob;
export type RunHistory = GitHubRun | JenkinsBuild;
export type PipelineStage = GitHubStage | JenkinsStage;

// Component state types
export type TabType = 'github' | 'jenkins';

export type SelectedWorkflow = CICDRun;

// --- Metrics types ---

export interface CicdDailyStat {
  date: string;         // "2026-05-20"
  successCount: number;
  failureCount: number;
  avgDuration: number;  // seconds
}

export interface CicdDora {
  deploymentFrequency: number; // deploys/day
  mttr: number;                // seconds
  changeFailureRate: number;   // percentage
}

export interface FailingStage {
  name: string;
  failureCount: number;
}

export interface FailingWorkflow {
  name: string;
  failureCount: number;
  successRate: number;
}

export interface CicdMetrics {
  successRate: number;
  totalRuns: number;
  failureCount: number;
  avgDurationSeconds: number;
  prevSuccessRate: number;
  prevTotalRuns: number;
  prevFailureCount: number;
  prevAvgDurationSeconds: number;
  dora: CicdDora | null;
  dailyStats: CicdDailyStat[];
  topFailingStages: FailingStage[];
  topFailingWorkflows: FailingWorkflow[];
}

export type TimeRange = 7 | 30 | 90;
