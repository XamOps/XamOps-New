import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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

  it('a scan opens on the narrative and the shift-left path, not a wall of CVEs', async () => {
    draw(<CodeTab data={demo.demoCodeScans} isLoading={false}
                  loadFindings={(async (fp: string) =>
                    demo.demoCodeScanFindings(fp)) as never} />);
    const scan = demo.demoCodeScans.scans.find(
      (s) => s.fingerprint && s.type === 'IMAGE_BUILD')!;
    fireEvent.click(screen.getByText(scan.commitSha!.slice(0, 7)).closest('tr')!);

    // Scoped to the panel: the scan table behind it has its own Repository/Branch/Commit headers.
    const panel = within(await screen.findByRole('dialog'));

    // The opening sentence: who pushed what, and how much of the total this build is answerable
    // for. That framing is the point of a shift-left view.
    expect(panel.getByText(/pushed/)).toBeInTheDocument();
    expect(panel.getByText(/introduced by this\s+build and/)).toBeInTheDocument();

    // The path Upwind draws as its "Shift left graph".
    expect(panel.getByText('Repository')).toBeInTheDocument();
    expect(panel.getByText('Branch')).toBeInTheDocument();
    expect(panel.getByText('Commit')).toBeInTheDocument();

    // Their terminal node is a deploy verdict the API does not carry, so ours ends on the
    // measured outcome and says so rather than inventing advice.
    const findings = demo.demoCodeScanFindings(scan.fingerprint!)!;
    // Appears twice by design, as in Upwind: once as the header verdict chip, once as the
    // terminal node of the path.
    expect(panel.getAllByText(
      findings.counts.introduced > 0
        ? `${findings.counts.introduced} introduced` : 'Nothing introduced').length)
      .toBeGreaterThan(0);
    // The panel used to explain that the deploy verdict is computed upstream and absent from the
    // payload. That is an internal integration detail, not something a customer needs, so it must
    // NOT be rendered.
    expect(panel.queryByText(/verdict is computed/)).not.toBeInTheDocument();

    // Resolved CVEs are a real bucket in the diff, so the panel reports it.
    expect(panel.getByText('Resolved CVEs')).toBeInTheDocument();
  });

  it('draws scan-volume bars inside a track with a definite height', () => {
    // jsdom does no layout, so it cannot tell a visible bar from a collapsed one. What it can
    // check is the cause of the collapse that shipped: the bar sizes itself with a percentage
    // height, and a percentage resolves against nothing unless its parent height is definite.
    // The row is `items-end`, so columns are never stretched — the track must carry its own
    // height class.
    const { container } = draw(
      <CodeTab data={demo.demoCodeScans} isLoading={false}
               loadFindings={demo.demoCodeScanFindings as never} />);

    // Selected by test id, not by colour class: the bar is tinted per scan kind now, so keying
    // on a Tailwind colour would break the moment the palette changes.
    const bars = Array.from(container.querySelectorAll<HTMLElement>('[data-testid="scan-bar"]'));
    expect(bars.length).toBeGreaterThan(0);
    for (const bar of bars) {
      expect(bar.style.height).toMatch(/^\d+(\.\d+)?%$/);
      const track = bar.parentElement!;
      // A definite height, not `height: 100%` against an unstretched flex column.
      expect(track.className).toMatch(/\bh-\d+\b/);
      expect(track.style.height).toBe('');
    }
    // The tallest day is full height, so the scale has a reachable top.
    expect(bars.some((b) => b.style.height === '100%')).toBe(true);
  });

  it('colours the volume bars by scan kind, the one dimension the API supports', () => {
    // Upwind tints its bars by deploy recommendation, which is absent from both payloads. Scan
    // kind is real, so the tab, the bars and the table cell share one palette per kind.
    const { container } = draw(
      <CodeTab data={demo.demoCodeScans} isLoading={false}
               loadFindings={demo.demoCodeScanFindings as never} />);
    const barClass = () => container
      .querySelector<HTMLElement>('[data-testid="scan-bar"]')!.className;

    expect(barClass()).toContain('bg-blue-500');          // image builds
    fireEvent.click(screen.getByRole('button', { name: /IaC scan/ }));
    expect(barClass()).toContain('bg-purple-500');         // IaC scans

    // Severity colours stay reserved for severity — a scan kind must not read as a risk level.
    expect(barClass()).not.toMatch(/bg-(red|orange|amber|emerald)-/);
  });

  it('lists CI scans newest first, so the table matches the period the chart plots', () => {
    // Upwind returns the stream oldest-first and nothing reordered it, so the table opened on
    // scans from weeks earlier while the chart showed the last 14 days. The two panels read as
    // describing unrelated periods.
    const { container } = draw(
      <CodeTab data={demo.demoCodeScans} isLoading={false}
               loadFindings={demo.demoCodeScanFindings as never} />);

    const scanned = Array.from(container.querySelectorAll('tbody tr'))
      .map((tr) => tr.querySelector('td:last-child')!.textContent!.trim())
      .filter((t) => t !== '—')
      .map((t) => new Date(t).getTime())
      .filter((t) => Number.isFinite(t));

    expect(scanned.length).toBeGreaterThan(1);
    for (let i = 1; i < scanned.length; i++) {
      expect(scanned[i]).toBeLessThanOrEqual(scanned[i - 1]);
    }
  });

  it('labels the scan-volume axis with the month, not bare day numbers', () => {
    // "31 01 02 … 13" gave no way to tell which fortnight the chart covered, which is why it
    // looked unrelated to the fully-qualified dates in the table underneath.
    const { container } = draw(
      <CodeTab data={demo.demoCodeScans} isLoading={false}
               loadFindings={demo.demoCodeScanFindings as never} />);

    const axis = Array.from(container.querySelectorAll<HTMLElement>('.whitespace-nowrap'))
      .map((el) => el.textContent!.trim())
      .filter((t) => /^\d{2}( \w+)?$/.test(t));

    expect(axis).toHaveLength(14);
    // The leftmost column always carries its month so the range is readable.
    expect(axis[0]).toMatch(/^\d{2} \w+$/);
  });

  it('says how many scans fall inside the charted window', () => {
    // The chart covers 14 days while the table lists every scan, so a bar total far below the
    // row count is expected rather than a contradiction — but only if it is stated.
    draw(<CodeTab data={demo.demoCodeScans} isLoading={false}
                  loadFindings={demo.demoCodeScanFindings as never} />);

    expect(screen.getByText(/of \d+ in last 14 days/)).toBeInTheDocument();
  });

  it('counts a scan from today in the final bucket', () => {
    // Bucket boundaries are calendar days, not fixed 86_400_000ms steps, which drift across a
    // DST change and move a day's scans into the neighbouring column.
    const today = new Date();
    const data = {
      ...demo.demoCodeScans,
      scans: [{
        ...demo.demoCodeScans.scans[0],
        type: 'IMAGE_BUILD',
        createTime: today.toISOString(),
      }],
    };

    const { container } = draw(
      <CodeTab data={data as never} isLoading={false}
               loadFindings={demo.demoCodeScanFindings as never} />);

    const bars = Array.from(container.querySelectorAll<HTMLElement>('[data-testid="scan-bar"]'));
    expect(bars).toHaveLength(1);

    // Last column = today. Its own bar must be the one that rendered.
    const columns = Array.from(container.querySelectorAll('.flex-1.flex.flex-col.items-center'));
    expect(columns).toHaveLength(14);
    expect(columns[13].contains(bars[0])).toBe(true);
  });

  it('Code separates IaC scans from image builds, as Upwind orders the page', () => {
    // A Terraform misconfiguration and a dependency CVE are not comparable rows, so one flat
    // table read as a single stream of unrelated builds.
    draw(<CodeTab data={demo.demoCodeScans} isLoading={false}
                  loadFindings={demo.demoCodeScanFindings as never} />);
    const iac = demo.demoCodeScans.scans.filter((s) => s.type === 'IAC_SCAN_BUILD').length;
    const img = demo.demoCodeScans.scans.filter((s) => s.type === 'IMAGE_BUILD').length;
    expect(iac).toBeGreaterThan(0);
    expect(img).toBeGreaterThan(0);

    // Image builds are the default view, and the table is scoped to the selected kind.
    const imageTab = screen.getByRole('button', { name: new RegExp(`Image build.*${img}`) });
    expect(imageTab).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(`${img} scans`)).toBeInTheDocument();

    const iacTab = screen.getByRole('button', { name: new RegExp(`IaC scan.*${iac}`) });
    fireEvent.click(iacTab);
    expect(iacTab).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(`${iac} scans`)).toBeInTheDocument();

    // The tab used to name two columns an upstream console has and the API does not. Removed:
    // it advertised a data gap to the customer. What remains is the actionable instruction.
    expect(screen.queryByText(/deploy recommendation per scan/)).not.toBeInTheDocument();
    expect(screen.getByText(/Open a scan to see its introduced/)).toBeInTheDocument();
  });

  it('Code says it is withheld rather than showing an empty table', () => {
    // Scans belong to a source repository, not a cloud account, so a tenant that connected no
    // repository is told that plainly instead of being shown a bare "no data" table. The wording
    // is about the reader's own accounts: that another tenant has CI at all is not their business.
    draw(<CodeTab isLoading={false} loadFindings={demo.demoCodeScanFindings as never}
                  data={{ scans: [], available: false,
                          unavailableReason:
                            'No code scanning is connected for your cloud accounts.' }} />);
    expect(screen.getByText(/Code scans not shown/)).toBeInTheDocument();
    expect(screen.getByText(/No code scanning is connected for your cloud accounts/))
      .toBeInTheDocument();
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

  it('Threats does not render an Investigation tab at all', () => {
    draw(
      <ThreatsSection threats={demo.demoThreatsPage('?page=0&size=50')} policies={demo.demoThreatPolicies}
                      stories={demo.demoThreatStories} overview={demo.demoThreatOverview}
                      isLoading={false} onPage={noop} onArchive={asyncNoop as never} />
    );
    // Previously rendered as a permanently disabled tab whose tooltip explained the missing
    // data source. A tab that can never be enabled advertises the gap, so it is not rendered.
    expect(screen.queryByRole('button', { name: /Investigation/ })).not.toBeInTheDocument();
    // The tabs that do work are still there.
    expect(screen.getByRole('button', { name: /Detections/ })).toBeInTheDocument();
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

  it('a framework opens its per-control breakdown with compliant-asset ratios', async () => {
    draw(
      <ConfigurationsTab dashboard={demo.demoConfigDashboard} posture={demo.demoPosturePage('?page=0&size=50')}
                         compliance={demo.demoCompliance} isLoading={false} onPage={noop}
                         loadControls={(async (id: string) => demo.demoFrameworkControls(id)) as never} />
    );
    fireEvent.click(screen.getByRole('button', { name: /Frameworks & rules/ }));
    fireEvent.click(screen.getByRole('button', { name: /CIS AWS Foundations/ }));

    // Controls arrive on open, behind their own nav item in the shared panel shell.
    const nav = await screen.findByRole('button', { name: /^Controls/ });
    fireEvent.click(nav);
    const control = await screen.findByText('Ensure S3 buckets block public access');
    expect(control).toBeInTheDocument();
    // The ratio is the number Upwind leads with, so it must be on the collapsed row.
    expect(screen.getAllByText(/\d+\/\d+ compliant/).length).toBeGreaterThan(0);

    // Remediation is behind the row, not printed for every control at once.
    expect(screen.queryByText(/Block all public access/)).not.toBeInTheDocument();
    fireEvent.click(control);
    expect(await screen.findByText(/Block all public access/)).toBeInTheDocument();
  });

  it('a control nothing was scored against reads as unscored, not 0% compliant', async () => {
    // Not in the demo fixtures because the live endpoint does not produce it: a control only
    // reaches us through its findings, and those carry pass or fail. Asserted directly so the
    // distinction survives, since reporting 0% would claim a failure that was never found.
    const unscored = async () => ({
      frameworkId: 'fw-x',
      controls: [{
        ruleId: 'fw-x-rule-001', title: 'Ensure KMS key rotation is enabled',
        severity: 'HIGH' as const, passCount: 0, failCount: 0, assetCount: 0,
        compliancePercent: null, unscoredCount: 3, resourceCount: 3,
        remediation: null, lastEvaluated: null,
      }],
      controlCount: 1,
      skippedOtherAccounts: 0,
    });
    draw(
      <ConfigurationsTab dashboard={demo.demoConfigDashboard} posture={demo.demoPosturePage('?page=0&size=50')}
                         compliance={demo.demoCompliance} isLoading={false} onPage={noop}
                         loadControls={unscored as never} />
    );
    fireEvent.click(screen.getByRole('button', { name: /Frameworks & rules/ }));
    fireEvent.click(screen.getByRole('button', { name: /CIS AWS Foundations/ }));
    fireEvent.click(await screen.findByRole('button', { name: /^Controls/ }));

    expect(await screen.findByText(/No control results were scored/)).toBeInTheDocument();
    expect(screen.queryByText('0%')).not.toBeInTheDocument();
    expect(screen.getByText('3 unscored')).toBeInTheDocument();
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

  it('an asset opens the provider references recorded against it', async () => {
    const asset = demo.demoInventoryAll.find(
      (a) => demo.demoAssetRelationships(a.upwindId!).relationships.length > 0)!;
    draw(
      <InventoryTab data={demo.demoInventoryPage('?page=0&size=50')} categories={demo.demoInventoryCategories}
                    risk={demo.demoInventoryRisk} network={demo.demoNetworkTopology}
                    isLoading={false} onLoad={noop}
                    loadRelationships={(async (id: string) => demo.demoAssetRelationships(id)) as never} />
    );
    fireEvent.click(screen.getByText(asset.name!).closest('tr')!);
    fireEvent.click(await screen.findByRole('button', { name: /Relationships/ }));

    const rels = demo.demoAssetRelationships(asset.upwindId!);
    expect(await screen.findByText(/Security Groups · 2/)).toBeInTheDocument();
    expect(screen.getByText(rels.relationships[0].references[0])).toBeInTheDocument();
    // The panel used to caveat that runtime connections come from sensor telemetry the API does
    // not expose. Removed: it explained an upstream limitation to the customer. The heading still
    // scopes the section to provider-recorded references.
    expect(screen.queryByText(/sensor telemetry/)).not.toBeInTheDocument();
    expect(screen.getByText(/References the cloud provider records/)).toBeInTheDocument();
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
