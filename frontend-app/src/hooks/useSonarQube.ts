/**
 * Custom hook for managing SonarQube projects and metrics
 * Handles fetching, filtering, and managing SonarQube data from the backend API
 *
 * Converted from: sonarqube.html (Alpine.js logic)
 */

import { useState, useEffect, useCallback } from 'react';
import { SonarQubeProject, SonarQubeMetrics, NewSonarQubeProject } from '../types/sonarqube';
import apiClient from '../utils/apiClient';

interface UseSonarQubeReturn {
  projects: SonarQubeProject[];
  selectedProjectId: string;
  metrics: SonarQubeMetrics | null;
  isLoading: boolean;
  isRefreshing: boolean;
  error: string;
  modalError: string;
  setSelectedProjectId: (id: string) => void;
  refreshMetrics: () => void;
  addProject: (project: NewSonarQubeProject) => Promise<void>;
  deleteProject: (projectId: number) => Promise<void>;
}

export const useSonarQube = (): UseSonarQubeReturn => {
  const [projects, setProjects] = useState<SonarQubeProject[]>([]);
  const [selectedProjectId, setSelectedProjectIdState] = useState<string>('');
  const [metrics, setMetrics] = useState<SonarQubeMetrics | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [modalError, setModalError] = useState('');

  /**
   * Fetch all projects from backend
   */
  const fetchProjects = useCallback(async (autoSelect = false) => {
    setIsLoading(true);
    setError('');

    try {
      console.log('🔧 Fetching SonarQube projects from API...');
      const data = await apiClient.get<SonarQubeProject[]>('/api/xamops/sonarqube/projects');

      setProjects(data || []);
      console.log(`✅ Loaded ${data?.length || 0} SonarQube projects`);

      if (autoSelect && data && data.length > 0) {
        const savedProjectId = sessionStorage.getItem('sonarSelectedProjectId');

        if (savedProjectId && data.some(p => p.id.toString() === savedProjectId)) {
          setSelectedProjectIdState(savedProjectId);
          fetchMetrics(savedProjectId);
        } else {
          const firstProjectId = data[0].id.toString();
          setSelectedProjectIdState(firstProjectId);
          fetchMetrics(firstProjectId);
        }
      }
    } catch (err: any) {
      console.error('❌ Failed to fetch SonarQube projects:', err);
      const errorMessage = err?.message || 'Failed to load projects. Please try again.';
      setError(errorMessage);
      setProjects([]);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Fetch metrics for a specific project
   */
  const fetchMetrics = useCallback(async (projectId: string) => {
    if (!projectId) {
      setMetrics(null);
      setError('');
      sessionStorage.removeItem('sonarSelectedProjectId');
      return;
    }

    sessionStorage.setItem('sonarSelectedProjectId', projectId);
    setIsLoading(true);
    setError('');
    setMetrics(null);

    try {
      console.log(`🔧 Fetching metrics for project ${projectId}...`);
      const data = await apiClient.get<SonarQubeMetrics>(`/api/xamops/sonarqube/projects/${projectId}/metrics`);

      setMetrics(data);
      console.log('✅ Loaded project metrics:', data);
    } catch (err: any) {
      console.error('❌ Failed to fetch metrics:', err);
      const errorMessage = err?.message || 'Failed to load metrics. Please try again.';
      setError(errorMessage);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Refresh metrics for current project
   */
  const refreshMetrics = useCallback(async () => {
    if (!selectedProjectId) return;

    setIsRefreshing(true);
    try {
      await fetchMetrics(selectedProjectId);
    } finally {
      setIsRefreshing(false);
    }
  }, [selectedProjectId, fetchMetrics]);

  /**
   * Add new project
   */
  const addProject = useCallback(async (project: NewSonarQubeProject) => {
    setModalError('');

    try {
      console.log('🔧 Adding new SonarQube project:', project.projectName);
      await apiClient.post('/api/xamops/sonarqube/projects', project);

      console.log('✅ Project added successfully');
      // Refetch projects without auto-selecting
      await fetchProjects(false);
    } catch (err: any) {
      console.error('❌ Failed to add project:', err);
      const errorMessage = err?.message || 'Failed to add project. Please try again.';
      setModalError(errorMessage);
      throw err;
    }
  }, [fetchProjects]);

  /**
   * Delete project
   */
  const deleteProject = useCallback(async (projectId: number) => {
    setModalError('');

    try {
      console.log(`🔧 Deleting SonarQube project ${projectId}...`);
      await apiClient.delete(`/api/xamops/sonarqube/projects/${projectId}`);

      console.log('✅ Project deleted successfully');

      // Refetch projects
      await fetchProjects(false);

      // Clear selection if deleted project was selected
      if (selectedProjectId === projectId.toString()) {
        setSelectedProjectIdState('');
        setMetrics(null);
        sessionStorage.removeItem('sonarSelectedProjectId');
      }
    } catch (err: any) {
      console.error('❌ Failed to delete project:', err);
      const errorMessage = err?.message || 'Failed to delete project. Please try again.';
      setModalError(errorMessage);
      throw err;
    }
  }, [selectedProjectId, fetchProjects]);

  /**
   * Set selected project and load its metrics
   */
  const setSelectedProjectId = useCallback((id: string) => {
    setSelectedProjectIdState(id);
    fetchMetrics(id);
  }, [fetchMetrics]);

  // Fetch projects on mount
  useEffect(() => {
    fetchProjects(true);
  }, [fetchProjects]);

  return {
    projects,
    selectedProjectId,
    metrics,
    isLoading,
    isRefreshing,
    error,
    modalError,
    setSelectedProjectId,
    refreshMetrics,
    addProject,
    deleteProject,
  };
};
