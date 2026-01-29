/**
 * TypeScript types for DevOps-in-the-Box functionality
 */

export type ScriptCategory = 'terraform' | 'bash' | 'python' | 'ansible';

export interface DevOpsScript {
  id: number;
  name: string;
  description: string;
  category: ScriptCategory;
  code: string;
  downloads: number;
  lastUpdated: string;
  version: string;
  usage: string;
}

export interface DevOpsFilters {
  searchTerm: string;
  filterCategory: 'all' | ScriptCategory;
}
