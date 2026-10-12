import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatusChip from '../StatusChip';
import Delta from '../Delta';
import KpiCard from '../KpiCard';
import FeatureMark, { featureIcon, featureName } from '../FeatureMark';
import StepIndicator from '../StepIndicator';
import NowBanner from '../NowBanner';
import HowItWorks from '../HowItWorks';
import { WORKLOAD_STATUS_TONES, RESULT_TONES, FEATURE_STATUS_TONES, resultKeyForTerminal } from '../statusTones';
import { deriveLifecycle } from '../lifecycle';
import { automationOverviewSentence } from '../nowSentence';
import StatusMark, { STATUS_ICONS } from '../../StatusMark';

describe('statusTones maps (glossary §2)', () => {
  it('each workload status pairs a tone with an icon', () => {
    for (const v of Object.values(WORKLOAD_STATUS_TONES)) {
      expect(v.tone).toBeTruthy();
      expect(v.icon.startsWith('mdi:')).toBe(true);
    }
  });

  it('each result pairs a tone with an icon', () => {
    for (const v of Object.values(RESULT_TONES)) {
      expect(v.icon.startsWith('mdi:')).toBe(true);
    }
  });

  it('feature status tones cover the §2.3 states', () => {
    expect(FEATURE_STATUS_TONES['on-apply']).toBe('good');
    expect(FEATURE_STATUS_TONES.unreachable).toBe('bad');
    expect(FEATURE_STATUS_TONES.off).toBe('off');
  });

  it('maps terminal outcomes to result keys, only APPLIED to applied', () => {
    expect(resultKeyForTerminal('APPLIED')).toBe('applied');
    expect(resultKeyForTerminal('DEFERRED')).toBe('deferred');
    expect(resultKeyForTerminal('INFEASIBLE')).toBe('infeasible');
    expect(resultKeyForTerminal('REFUSED')).toBe('refused');
    expect(resultKeyForTerminal('SOMETHING_NEW')).toBe('failed');
  });
});

describe('StatusChip', () => {
  it('renders its icon and non-empty label and tags the tone', () => {
    const { container } = render(<StatusChip tone="good" icon="mdi:check-circle" label="Applied" detail="watching" />);
    expect(screen.getByText('Applied')).toBeTruthy();
    expect(screen.getByText('watching')).toBeTruthy();
    expect(container.querySelector('[data-tone="good"]')).toBeTruthy();
    expect(container.querySelector('svg')).toBeTruthy();
    expect(container.querySelector('.text-status-good')).toBeTruthy();
  });
});

describe('Delta', () => {
  it('is green for saved and amber for extra, with the amount', () => {
    const saved = render(<Delta direction="saved" amount="$42/mo" suffix="saved" />);
    expect(saved.container.querySelector('.text-money-saved')).toBeTruthy();
    expect(screen.getByText('$42/mo')).toBeTruthy();
    saved.unmount();
    const extra = render(<Delta direction="extra" amount="$5/mo" />);
    expect(extra.container.querySelector('.text-money-extra')).toBeTruthy();
  });
});

describe('KpiCard', () => {
  it('shows the tinted label with its icon and the number in ink', () => {
    const { container } = render(
      <KpiCard tone="warn" icon="mdi:cash" label="Can save now" value="$42/mo"><span>waiting for you</span></KpiCard>,
    );
    expect(screen.getByText('Can save now')).toBeTruthy();
    expect(container.querySelector('.text-status-warn')).toBeTruthy();
    expect(screen.getByText('$42/mo')).toBeTruthy();
    expect(screen.getByText('waiting for you')).toBeTruthy();
  });
});

describe('FeatureMark', () => {
  it('draws the feature tile and name', () => {
    const { container } = render(<FeatureMark feature="workload" />);
    expect(screen.getByText('Workload autoscaler')).toBeTruthy();
    expect(container.querySelector('[data-feature="workload"]')).toBeTruthy();
    expect(container.querySelector('.text-feature-workload')).toBeTruthy();
    expect(featureName('hpa')).toBe('HPA');
    expect(featureIcon('vpa')).toBe('mdi:compare-vertical');
  });
});

describe('StepIndicator', () => {
  it('renders five bars and a label in the current stage tone', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'RUNNING' });
    const { container } = render(<StepIndicator states={states} />);
    expect(container.querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThanOrEqual(5);
    expect(screen.getByText('Applying')).toBeTruthy();
  });
});

describe('NowBanner', () => {
  it('renders the sentence and its chips', () => {
    const sentence = automationOverviewSentence({ cluster: 'c', watching: 3, ready: 1, readyMoney: '$9/mo' });
    const { container } = render(<NowBanner sentence={sentence} />);
    expect(container.querySelector('[data-now-banner]')).toBeTruthy();
    expect(screen.getByText(/watching 3 workloads/)).toBeTruthy();
    expect(screen.getByText('1 can be resized, $9/mo')).toBeTruthy();
  });
});

describe('HowItWorks', () => {
  it('renders the five steps and highlights the first problem step', () => {
    const { container } = render(
      <HowItWorks chips={{ apply: { tone: 'warn', icon: 'mdi:clock-alert-outline', label: 'Waiting for you' } }} />,
    );
    expect(screen.getByText('1. Measure')).toBeTruthy();
    expect(screen.getByText('5. Watch')).toBeTruthy();
    const apply = container.querySelector('[data-how-step="apply"]')!;
    expect(apply.className).toContain('border-status-warn');
  });

  it('starts collapsed with a Show toggle', () => {
    render(<HowItWorks chips={{}} collapsed summary="2 ready" />);
    expect(screen.getByText(/Measure › Decide › Apply › Verify › Watch/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Show' })).toBeTruthy();
  });
});

describe('StatusMark compatibility', () => {
  it('keeps the STATUS_ICONS map and renders label + detail', () => {
    expect(STATUS_ICONS.healthy).toBe('mdi:check-circle');
    expect(STATUS_ICONS.error).toBe('mdi:alert-octagon');
    render(<StatusMark state="attention" label="Data stale" detail="3 h" />);
    expect(screen.getByText('Data stale')).toBeTruthy();
    expect(screen.getByText('3 h')).toBeTruthy();
  });
});
