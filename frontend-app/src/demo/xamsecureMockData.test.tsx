import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsSection, VulnerabilitiesTab,
  ConfigurationsTab, InventoryTab, ApiSecurityTab, ComponentsTab, WorkflowsTab, OnboardingTab,
} from '../components/XamSecure';
import * as demo from './xamsecureMockData';

/**
 * Renders every XamSecure tab against the demo dataset. The router test proves the
 * payloads are *served*; this proves they are *shaped* the way each tab reads them —
 * a field the type marks optional but a component dereferences would only surface here.
 */
const draw = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

const noop = () => undefined;
const asyncNoop = async () => [];

describe('XamSecure demo data renders in every tab', () => {
  it('Home shows the issue counts and threat feed', () => {
    draw(
      <OverviewTab summary={demo.demoXamSecureSummary} issues={demo.demoIssues}
                   threats={demo.demoThreatsPage('?page=0&size=50')} stories={demo.demoThreatStories}
                   vulnDashboard={demo.demoVulnDashboard} isLoading={false} />
    );
    expect(screen.getByText(`${demo.demoIssues.length} total issues`)).toBeInTheDocument();
    expect(screen.getByText(/Crypto-mining binary/)).toBeInTheDocument();
    // Data-freshness panel is driven by summary.syncState
    expect(screen.getByText('vulnerabilities')).toBeInTheDocument();
  });

  it('Issues lists the grouped checks', () => {
    draw(<IssuesTab data={demo.demoIssues} isLoading={false} loadDetail={demo.demoIssueDetail as never} />);
    expect(screen.getByText('S3 bucket allows public read access')).toBeInTheDocument();
  });

  it('Attack Surface plots the exposed resources', () => {
    draw(<AttackSurfaceTab data={demo.demoAttackSurface} isLoading={false} loadDetail={demo.demoIssueDetail as never} />);
    expect(screen.getAllByText(/S3 Buckets/).length).toBeGreaterThan(0);
  });

  it('Threats lists detections and policies', () => {
    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    expect(screen.getAllByText(/Reverse shell spawned/).length).toBeGreaterThan(0);
  });

  it('Vulnerabilities shows the CVE totals', () => {
    draw(
      <VulnerabilitiesTab dashboard={demo.demoVulnDashboard} byCve={demo.demoVulnByCvePage('?page=0&size=50')}
                          isLoading={false} onCvePage={noop} loadCveFindings={asyncNoop as never} />
    );
    expect(screen.getByText('Total CVEs')).toBeInTheDocument();
    expect(screen.getByText(demo.demoCveRowsAll.length.toLocaleString())).toBeInTheDocument();

    // Switch to the Findings view — the CVE table lives behind that toggle
    fireEvent.click(screen.getByText('Findings'));
    expect(screen.getByText('CVE-2024-3094')).toBeInTheDocument();
    expect(screen.getByText(`${demo.demoCveRowsAll.length} CVEs`)).toBeInTheDocument();
  });

  it('Configurations shows framework scores and posture findings', () => {
    draw(
      <ConfigurationsTab dashboard={demo.demoConfigDashboard} posture={demo.demoPosturePage('?page=0&size=50')}
                         compliance={demo.demoCompliance} isLoading={false} onPage={noop} />
    );
    expect(screen.getAllByText(/CIS AWS Foundations/).length).toBeGreaterThan(0);
  });

  it('Inventory lists assets and categories', () => {
    draw(
      <InventoryTab data={demo.demoInventoryPage('?page=0&size=50')} categories={demo.demoInventoryCategories}
                    network={demo.demoNetworkTopology} isLoading={false} onLoad={noop} />
    );
    expect(screen.getByText(demo.demoInventoryAll[0].name!)).toBeInTheDocument();
    // Category rail is rendered from the derived counts
    expect(screen.getAllByText(/networking/i).length).toBeGreaterThan(0);
  });

  it('APIs lists discovered endpoints', () => {
    draw(<ApiSecurityTab data={demo.demoApiSecurity} isLoading={false} />);
    expect(screen.getByText('/api/v1/checkout')).toBeInTheDocument();
  });

  it('Components lists SBOM packages', () => {
    draw(<ComponentsTab data={demo.demoComponents} isLoading={false} />);
    expect(screen.getAllByText('log4j-core').length).toBeGreaterThan(0);
  });

  it('Workflows lists automations', () => {
    draw(<WorkflowsTab data={demo.demoWorkflows} isLoading={false} />);
    expect(screen.getByText(/Critical threat → PagerDuty/)).toBeInTheDocument();
  });

  it('Onboarding lists the demo cloud accounts', () => {
    draw(<OnboardingTab accounts={demo.demoOnboarding} isLoading={false} />);
    expect(screen.getByText('Xammer Production')).toBeInTheDocument();
    expect(screen.getByText(/helm upgrade --install upwind-sensor/)).toBeInTheDocument();
  });
});
