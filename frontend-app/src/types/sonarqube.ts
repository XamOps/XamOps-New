/**
 * TypeScript types for SonarQube Code Quality Dashboard
 *
 * Converted from: sonarqube.html
 */

/**
 * SonarQube Project Configuration
 */
export interface SonarQubeProject {
  id: number;
  projectName: string;
  projectKey: string;
  serverUrl: string;
  token: string;
}

/**
 * Quality Gate Status
 */
export type QualityGateStatus = 'OK' | 'PASSED' | 'ERROR' | 'FAILED' | 'WARN' | 'NONE';

/**
 * Rating (A-E)
 */
export type Rating = 'A' | 'B' | 'C' | 'D' | 'E';

/**
 * SonarQube Metrics for a Project
 */
export interface SonarQubeMetrics {
  qualityGateStatus: QualityGateStatus;
  linesOfCode: number;
  bugs: number;
  vulnerabilities: number;
  securityHotspots: number;
  codeSmells: number;
  coverage: number;
  duplicationDensity: number;
  duplicatedLines: number;
  linesToCover: number;
  techDebt: number; // in minutes
  reliabilityRating: Rating;
  securityRating: Rating;
  securityReviewRating: Rating;
  maintainabilityRating: Rating;
}

/**
 * New Project Form Data
 */
export interface NewSonarQubeProject {
  projectName: string;
  serverUrl: string;
  projectKey: string;
  token: string;
}
