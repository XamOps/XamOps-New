import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { gcpAdapter } from '../gcpAdapter';
import type { GroupDetail, Plan, PlanBodyDoc } from '../../../../api/autospottingApi';

const detail = { currentOperation: null } as unknown as GroupDetail;

const TEMPLATE = 'https://www.googleapis.com/compute/v1/projects/p/global/instanceTemplates/web-tpl-1';

describe('gcpAdapter', () => {
  it('names the GCP group, location and official icon', () => {
    expect(gcpAdapter.provider).toBe('GCP');
    expect(gcpAdapter.groupNoun).toBe('Managed Instance Group');
    expect(gcpAdapter.locationLabel).toBe('Location');
    expect(gcpAdapter.icon).toBe('/icons/gcp-compute-engine.png');
    expect(gcpAdapter.columns.map(c => c.header)).toEqual(['Machine type']);
  });

  it('renders the MIG config: templates, scope, distribution, update policy, floor and preemptions', () => {
    const { ConfigFields } = gcpAdapter;
    render(
      <ConfigFields
        group={detail}
        current={{
          locationType: 'REGIONAL',
          location: 'us-central1',
          instanceTemplate: TEMPLATE,
          versions: [{ instanceTemplate: TEMPLATE }],
          distributionZones: ['us-central1-a', 'us-central1-b', 'us-central1-c'],
          updatePolicy: { type: 'OPPORTUNISTIC', minimalAction: 'REPLACE', maxSurge: { fixed: 3 } },
        }}
      />,
    );
    expect(screen.getByText('web-tpl-1')).toBeInTheDocument();
    expect(screen.getByText('Regional (us-central1)')).toBeInTheDocument();
    expect(screen.getByText('3 zones: us-central1-a, us-central1-b, us-central1-c')).toBeInTheDocument();
    expect(screen.getByText('OPPORTUNISTIC, minimal action REPLACE, max surge 3')).toBeInTheDocument();
    expect(screen.getByText('None (not converted)')).toBeInTheDocument();
    expect(screen.getByText('Preemptions in the last 7 days')).toBeInTheDocument();
    expect(screen.getByText('Not tracked yet')).toBeInTheDocument();
  });

  it('shows the recorded preemption count of the last 7 days once the group has one', () => {
    const { ConfigFields } = gcpAdapter;
    render(<ConfigFields group={{ ...detail, preemptions7d: 4 } as unknown as GroupDetail} current={null} />);
    expect(screen.getByText('Preemptions in the last 7 days')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.queryByText('Not tracked yet')).not.toBeInTheDocument();
  });
  it('shows zero preemptions as 0, never as not tracked', () => {
    const { ConfigFields } = gcpAdapter;
    render(<ConfigFields group={{ ...detail, preemptions7d: 0 } as unknown as GroupDetail} current={null} />);
    expect(screen.getByText('0')).toBeInTheDocument();
  });
  it('says a field is shown at plan time when no plan is loaded', () => {
    const { ConfigFields } = gcpAdapter;
    render(<ConfigFields group={detail} current={null} />);
    expect(screen.getAllByText('Shown when you review a plan').length).toBeGreaterThanOrEqual(4);
  });

  it('renders the plan target: two-version floor, versions and the rollout policy', () => {
    const { PlanTargetFields } = gcpAdapter;
    const body = {
      target: {
        floorMechanism: 'TWO_VERSION_MIG',
        versions: [
          { name: 'xas-standard', instanceTemplate: TEMPLATE, targetSize: { fixed: 3 } },
          { name: 'xas-spot', instanceTemplate: '(XamOps Spot copy, created at enable)' },
        ],
        updatePolicy: { type: 'PROACTIVE', minimalAction: 'REPLACE', maxSurge: { fixed: 3 }, maxUnavailable: { fixed: 0 } },
      },
      onDemandFloor: { floorN: 3 },
    } as unknown as PlanBodyDoc;
    render(<PlanTargetFields plan={{} as Plan} body={body} />);
    expect(screen.getByText('Two template versions (standard floor plus Spot)')).toBeInTheDocument();
    expect(screen.getByText('xas-standard (fixed 3), xas-spot (the remainder)')).toBeInTheDocument();
    expect(screen.getByText('3 standard VMs')).toBeInTheDocument();
    expect(screen.getByText('PROACTIVE, minimal action REPLACE, max surge 3, max unavailable 0')).toBeInTheDocument();
  });

  it('gives GCP permission guidance', () => {
    expect(gcpAdapter.permissionGuidance('GCP_PERMISSION').text).toContain('service account');
    expect(gcpAdapter.permissionGuidance('PERMISSION').text).toContain('Compute Engine');
  });
});
