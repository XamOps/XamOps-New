import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  IssuesTab, AttackSurfaceTab, OverviewTab, ThreatsSection, VulnerabilitiesTab,
  ConfigurationsTab, InventoryTab, ApiSecurityTab, ComponentsTab, CodeTab, WorkflowsTab, OnboardingTab,
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

  it('Vulnerabilities offers Upwind\'s runtime-exposure view and it narrows the list', () => {
    const all = demo.demoVulnByCvePage('?page=0&size=50');
    draw(
      <VulnerabilitiesTab dashboard={demo.demoVulnDashboard} byCve={all}
                          isLoading={false} onCvePage={noop}
                          loadCveFindings={asyncNoop as never}
                          onCveSeverity={noop} onCveView={noop} />
    );
    fireEvent.click(screen.getByRole('button', { name: /^Findings/ }));
    // Both counts are offered up front: the installed total stays reachable rather than being
    // replaced by a hidden default filter, which is what makes Upwind's console read so low.
    const runtimeChip = screen.getByRole('button', { name: /Runtime exposed/ });
    expect(runtimeChip).toBeInTheDocument();
    expect(runtimeChip).toHaveAttribute('aria-pressed', 'false');

    // The runtime view really is a strict subset.
    const runtime = demo.demoVulnByCvePage('?page=0&size=50&view=runtime');
    expect(runtime.view).toBe('runtime');
    expect(runtime.totalElements).toBeLessThan(all.totalElements);
    expect(runtime.runtimeExposedCount).toBe(runtime.totalElements);
  });

  it('Vulnerabilities groups findings by image, package and resource', () => {
    // The unit of remediation is usually an image or a package, not a CVE, which is why Upwind
    // offers these dimensions alongside it.
    for (const dim of ['image', 'package', 'resource'] as const) {
      const groups = demo.demoVulnGroupPage(`?dimension=${dim}&page=0&size=50`);
      expect(groups.dimension).toBe(dim);
      expect(groups.totalElements).toBeGreaterThan(0);
      // Every group has to account for at least one CVE, or the rollup is lying.
      expect(groups.content.every((g) => g.cveCount > 0)).toBe(true);
    }

    const byImage = demo.demoVulnGroupPage('?dimension=image&page=0&size=50');
    draw(
      <VulnerabilitiesTab dashboard={demo.demoVulnDashboard}
                          byCve={demo.demoVulnByCvePage('?page=0&size=50')}
                          groups={byImage} onGroupBy={noop}
                          isLoading={false} onCvePage={noop}
                          loadCveFindings={asyncNoop as never} />
    );
    fireEvent.click(screen.getByRole('button', { name: /^Findings/ }));
    fireEvent.click(screen.getByRole('button', { name: /^Image$/ }));
    expect(screen.getByText('Tag')).toBeInTheDocument();
  });

  it('Threats groups detections by threat, resource and account', () => {
    const byThreat = demo.demoThreatGroups('?dimension=threat');
    // Grouping must actually collapse the feed, otherwise the chip achieves nothing.
    expect(byThreat.groups.length).toBeLessThan(demo.demoThreatsAll.length);
    expect(byThreat.groups.every((g) => g.detections > 0)).toBe(true);
    // Occurrences count repeats, so they can never be fewer than the detections themselves.
    expect(byThreat.groups.every((g) => g.occurrences >= g.detections)).toBe(true);

    expect(demo.demoThreatGroups('?dimension=account').groups.length)
      .toBeLessThanOrEqual(byThreat.groups.length);

    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      stories={demo.demoThreatStories} overview={demo.demoThreatOverview}
                      groups={byThreat} onGroupBy={noop}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    fireEvent.click(screen.getByRole('button', { name: /^Detections/ }));
    fireEvent.click(screen.getByRole('button', { name: /^Threat$/ }));
    expect(screen.getByText('Occurrences')).toBeInTheDocument();
  });

  it('Code lists CI scans and separates introduced CVEs from pre-existing ones', () => {
    draw(<CodeTab data={demo.demoCodeScans} isLoading={false}
                  loadFindings={demo.demoCodeScanFindings as never} />);
    expect(screen.getAllByText('XamOps-New').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Image build|IaC scan/).length).toBeGreaterThan(0);

    // The bucket split is the whole point: a build that adds nothing must not look like a build
    // that adds a critical CVE, even when both carry pre-existing debt.
    const withFp = demo.demoCodeScans.scans.filter((s) => s.fingerprint);
    const introducing = withFp
      .map((s) => demo.demoCodeScanFindings(s.fingerprint!))
      .filter((f) => f && f.counts.introduced > 0);
    const clean = withFp
      .map((s) => demo.demoCodeScanFindings(s.fingerprint!))
      .filter((f) => f && f.counts.introduced === 0);
    expect(introducing.length).toBeGreaterThan(0);
    expect(clean.length).toBeGreaterThan(0);
    // Pre-existing CVEs exist on both, so they cannot be what distinguishes them.
    expect(clean.every((f) => f!.counts.noChange > 0)).toBe(true);
    // Worst first within a bucket.
    expect(introducing[0]!.cves.introduced[0].severity).toBe('CRITICAL');
    // Fingerprint-less scans have no findings to open.
    expect(demo.demoCodeScanFindings('fp-does-not-exist')).toBeNull();
  });

  it('Code says it is withheld rather than showing an empty table', () => {
    // Shift-left data is org-level and carries no cloud account, so a client that does not own
    // the whole org must be told why, not shown a bare "no data".
    draw(<CodeTab isLoading={false} loadFindings={demo.demoCodeScanFindings as never}
                  data={{ scans: [], available: false,
                          unavailableReason: 'Only shown to the account owner.' }} />);
    expect(screen.getByText(/Code scans not shown/)).toBeInTheDocument();
    expect(screen.getByText(/Only shown to the account owner/)).toBeInTheDocument();
  });

  it('Threats opens on the dashboard and reaches detections', () => {
    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      stories={demo.demoThreatStories} overview={demo.demoThreatOverview}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    // Dashboard is the landing view, as it is in Upwind.
    expect(screen.getByText(/Noise to signal threat funnel/)).toBeInTheDocument();
    expect(screen.getByText('Defense Evasion')).toBeInTheDocument();
    // The events stage has no equivalent on our side, so it must say so, not show a number.
    expect(screen.getByText('Not available')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /^Detections/ }));
    expect(screen.getAllByText(/Reverse shell spawned/).length).toBeGreaterThan(0);
  });

  it('Threats lists stories with their related event counts', () => {
    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      stories={demo.demoThreatStories} overview={demo.demoThreatOverview}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    fireEvent.click(screen.getByRole('button', { name: /^Stories/ }));
    expect(screen.getAllByText(/Compromised container mining cryptocurrency/).length)
      .toBeGreaterThan(0);
  });

  it('Threats leaves Investigation disabled, since we sync detections and not events', () => {
    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      stories={demo.demoThreatStories} overview={demo.demoThreatOverview}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    expect(screen.getByRole('button', { name: /Investigation/ })).toBeDisabled();
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
                    risk={demo.demoInventoryRisk} network={demo.demoNetworkTopology}
                    isLoading={false} onLoad={noop} />
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
