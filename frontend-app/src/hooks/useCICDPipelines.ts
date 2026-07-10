/**
 * Custom hook for managing CICD Pipelines data
 * Handles fetching GitHub Actions and Jenkins jobs from the backend API
 */

import { useState, useEffect, useCallback } from 'react';
import {
  GitHubRun,
  GitHubRepository,
  JenkinsJob,
  RunHistory,
  PipelineStage,
  SelectedWorkflow,
  TabType,
} from '../types/cicdPipelines';
import apiClient from '../utils/apiClient';

interface UseCICDPipelinesReturn {
  // GitHub data
  githubRuns: GitHubRun[];
  isLoadingGithub: boolean;
  githubAuthError: boolean;
  githubRepoError: boolean;
  fetchGithubData: () => Promise<void>;

  // Jenkins data
  jenkinsJobs: JenkinsJob[];
  isLoadingJenkins: boolean;
  fetchJenkinsData: () => Promise<void>;

  // Active tab
  activeTab: TabType;
  setActiveTab: (tab: TabType) => void;

  // Selected workflow and history
  selectedWorkflow: SelectedWorkflow | null;
  workflowHistory: RunHistory[];
  isLoadingHistory: boolean;
  viewWorkflowHistory: (item: GitHubRun | JenkinsJob) => Promise<void>;
  closeWorkflow: () => void;

  // Selected run and stages
  selectedRun: RunHistory | null;
  selectedRunNumber: string;
  selectedRunUrl: string;
  pipelineStages: PipelineStage[];
  isStagesLoading: boolean;
  selectRunFromHistory: (run: RunHistory) => Promise<void>;

  // Utility functions
  refreshData: () => Promise<void>;

  autoOpenLog: boolean;
  viewError: (item: GitHubRun | JenkinsJob) => Promise<void>;
}

export const useCICDPipelines = (): UseCICDPipelinesReturn => {
  // GitHub state
  const [githubRuns, setGithubRuns] = useState<GitHubRun[]>([]);
  const [isLoadingGithub, setIsLoadingGithub] = useState(false);
  const [githubAuthError, setGithubAuthError] = useState(false);
  const [githubRepoError, setGithubRepoError] = useState(false);

  // Jenkins state
  const [jenkinsJobs, setJenkinsJobs] = useState<JenkinsJob[]>([]);
  const [isLoadingJenkins, setIsLoadingJenkins] = useState(false);

  // Tab state
  const [activeTab, setActiveTab] = useState<TabType>('github');

  // Workflow modal state
  const [selectedWorkflow, setSelectedWorkflow] = useState<SelectedWorkflow | null>(null);
  const [workflowHistory, setWorkflowHistory] = useState<RunHistory[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  // Run detail state
  const [selectedRun, setSelectedRun] = useState<RunHistory | null>(null);
  const [selectedRunNumber, setSelectedRunNumber] = useState('');
  const [selectedRunUrl, setSelectedRunUrl] = useState('');
  const [pipelineStages, setPipelineStages] = useState<PipelineStage[]>([]);
  const [isStagesLoading, setIsStagesLoading] = useState(false);
  const [autoOpenLog, setAutoOpenLog] = useState(false);

  /**
   * Fetch GitHub Actions runs
   */
  const fetchGithubData = useCallback(async () => {
    setIsLoadingGithub(true);
    try {
      console.log('🔧 Fetching GitHub Actions runs...');
      // Use the raw fetch so we can inspect the X-Github-Auth-Error response header,
      // which the backend sets when a stored PAT is invalid/expired.
      const response = await apiClient.fetch('/api/cicd/github/runs', { method: 'GET' });

      setGithubAuthError(response.headers.get('X-Github-Auth-Error') === 'true');
      setGithubRepoError(response.headers.get('X-Github-Repo-Error') === 'true');

      let data: GitHubRun[] = [];
      if (response.ok) {
        const text = await response.text();
        const parsed = text ? JSON.parse(text) : [];
        data = Array.isArray(parsed) ? parsed : [];
      }
      setGithubRuns(data);
      console.log(`✅ Loaded ${data.length} GitHub runs`);
    } catch (err: any) {
      console.error('❌ Failed to fetch GitHub data:', err);
      setGithubRuns([]);
    } finally {
      setIsLoadingGithub(false);
    }
  }, []);

  /**
   * Fetch Jenkins jobs
   */
  const fetchJenkinsData = useCallback(async () => {
    setIsLoadingJenkins(true);
    try {
      console.log('🔧 Fetching Jenkins jobs...');
      const response = await apiClient.get('/api/cicd/jenkins/jobs');

      // Accept both arrays (including empty arrays) as valid responses
      const data = Array.isArray(response) ? response : [];
      setJenkinsJobs(data);
      console.log(`✅ Loaded ${data.length} Jenkins jobs`);
    } catch (err: any) {
      console.error('❌ Failed to fetch Jenkins data:', err);
      setJenkinsJobs([]);
    } finally {
      setIsLoadingJenkins(false);
    }
  }, []);

  /**
   * Refresh all data
   */
  const refreshData = useCallback(async () => {
    await Promise.all([fetchGithubData(), fetchJenkinsData()]);
  }, [fetchGithubData, fetchJenkinsData]);

  /**
   * View workflow history
   */
  const viewWorkflowHistory = useCallback(async (item: GitHubRun | JenkinsJob) => {
    setSelectedWorkflow(item as SelectedWorkflow);
    setIsLoadingHistory(true);
    setWorkflowHistory([]);
    setPipelineStages([]);
    setSelectedRun(null);

    try {
      // Check if it's a GitHub run
      if ('repository' in item && item.repository) {
        const [owner, repo] = item.repository.full_name.split('/');
        const workflowId = item.workflow_id;

        console.log(`🔧 Fetching GitHub workflow history for ${owner}/${repo}/${workflowId}`);
        const response = await apiClient.get(
          `/api/cicd/github/runs/${owner}/${repo}/${workflowId}?count=15`
        );

        const data = Array.isArray(response) ? response : [];
        setWorkflowHistory(data);
        console.log(`✅ Loaded ${data.length} workflow runs`);

        // Select first run by default
        if (data.length > 0) {
          await selectRunFromHistory(data[0]);
        }
      }
      // It's a Jenkins job
      else if ('lastBuild' in item && item.lastBuild) {
        console.log(`🔧 Fetching Jenkins build history for ${item.name}`);
        const response = await apiClient.get(`/api/cicd/jenkins/jobs/${item.name}/builds`);

        const data = Array.isArray(response) ? response : [];
        if (data.length > 0) {
          setWorkflowHistory(data);
          console.log(`✅ Loaded ${data.length} Jenkins builds`);

          // Select first build by default
          await selectRunFromHistory(data[0]);
        } else {
          // Fallback: create a single run from lastBuild
          const fallbackRun = {
            run_number: item.lastBuild.number,
            number: item.lastBuild.number,
            name: item.name,
            displayStatus: item.displayStatus,
            updated_at: new Date(item.lastBuild.timestamp).toISOString(),
            timestamp: item.lastBuild.timestamp,
            html_url: item.lastBuild.url,
            url: item.lastBuild.url,
            result: item.displayStatus.toUpperCase() as any,
          };
          setWorkflowHistory([fallbackRun]);
          await selectRunFromHistory(fallbackRun);
        }
      }
    } catch (err) {
      console.error('❌ Failed to fetch workflow history:', err);
      setWorkflowHistory([]);
    } finally {
      setIsLoadingHistory(false);
    }
  }, []);

  /**
   * Select a run from history and fetch its stages
   */
  const selectRunFromHistory = useCallback(async (run: RunHistory) => {
    setSelectedRun(run);
    setIsStagesLoading(true);
    setPipelineStages([]);

    // Determine run number
    const runNumber = 'run_number' in run ? run.run_number : ('number' in run ? (run as any).number : ('id' in run ? (run as any).id : ''));
    setSelectedRunNumber(String(runNumber));

    // Determine run URL
    const runUrl = 'html_url' in run ? run.html_url : 'url' in run ? run.url : '';
    setSelectedRunUrl(runUrl);

    try {
      // Check if it's a GitHub run
      if (selectedWorkflow && 'repository' in selectedWorkflow && selectedWorkflow.repository) {
        const repository = selectedWorkflow.repository as GitHubRepository;
        const [owner, repo] = repository.full_name.split('/');
        const runId = 'id' in run ? run.id : '';

        console.log(`🔧 Fetching GitHub run stages for ${owner}/${repo}/${runId}`);
        const response = await apiClient.get(
          `/api/cicd/github/runs/${owner}/${repo}/${runId}/stages`
        );

        const data = Array.isArray(response) ? response : [];
        setPipelineStages(data);
        console.log(`✅ Loaded ${data.length} stages`);
      }
      // It's a Jenkins build
      else if (selectedWorkflow) {
        const jobName = selectedWorkflow.name;

        console.log(`🔧 Fetching Jenkins build stages for ${jobName}/${runNumber}`);
        const response = await apiClient.get(
          `/api/cicd/jenkins/jobs/${jobName}/${runNumber}/stages`
        );

        const data = Array.isArray(response) ? response : [];
        setPipelineStages(data);
        console.log(`✅ Loaded ${data.length} stages`);
      }
    } catch (err) {
      console.error('❌ Failed to fetch pipeline stages:', err);
      setPipelineStages([]);
    } finally {
      setIsStagesLoading(false);
    }
  }, [selectedWorkflow]);

  /**
   * Close workflow modal
   */
  const closeWorkflow = useCallback(() => {
    setAutoOpenLog(false);
    setSelectedWorkflow(null);
    setWorkflowHistory([]);
    setSelectedRun(null);
    setPipelineStages([]);
  }, []);

  /**
   * View error — opens workflow history with log auto-expanded
   */
  const viewError = useCallback(async (item: GitHubRun | JenkinsJob) => {
    setAutoOpenLog(true);
    await viewWorkflowHistory(item);
  }, [viewWorkflowHistory]);

  // Fetch data on mount
  useEffect(() => {
    fetchGithubData();
    fetchJenkinsData();
  }, [fetchGithubData, fetchJenkinsData]);

  return {
    // GitHub
    githubRuns,
    isLoadingGithub,
    githubAuthError,
    githubRepoError,
    fetchGithubData,

    // Jenkins
    jenkinsJobs,
    isLoadingJenkins,
    fetchJenkinsData,

    // Tab
    activeTab,
    setActiveTab,

    // Workflow
    selectedWorkflow,
    workflowHistory,
    isLoadingHistory,
    viewWorkflowHistory,
    closeWorkflow,

    // Run details
    selectedRun,
    selectedRunNumber,
    selectedRunUrl,
    pipelineStages,
    isStagesLoading,
    selectRunFromHistory,

    // Utilities
    refreshData,

    autoOpenLog,
    viewError,
  };
};
