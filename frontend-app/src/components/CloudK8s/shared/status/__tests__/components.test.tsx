import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatusChip from '../StatusChip';
import Delta from '../Delta';
import KpiCard from '../KpiCard';
import FeatureMark, { FeatureDot, featureIcon, featureName } from '../FeatureMark';
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

  it('FeatureDot is the accent as a decorative dot, for use next to the feature name', () => {
    const { container } = render(<FeatureDot feature="hpa" />);
    const dot = container.querySelector('[data-feature-dot="hpa"]')!;
    expect(dot.getAttribute('aria-hidden')).toBe('true');
    expect(dot.className).toContain('bg-feature-hpa');
    expect(dot.textContent).toBe('');
  });
});

describe('StepIndicator', () => {
  it('renders five bars and a label in the current stage tone', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'RUNNING' });
    const { container } = render(<StepIndicator states={states} />);
    expect(container.querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThanOrEqual(5);
    expect(screen.getByText('Applying')).toBeTruthy();
  });

  it('full form: every stage with its icon, label and line, the current one marked', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'APPLIED', appliedOpen: true, hasCooldown: true, cooldownEnded: false });
    const { container } = render(
      <StepIndicator variant="full" states={states} details={{ watching: 'Next change allowed at 5:13 PM IST' }} />,
    );
    const stages = [...container.querySelectorAll('[data-stage]')];
    expect(stages.map((s) => s.getAttribute('data-stage-status'))).toEqual(['done', 'done', 'done', 'done', 'current']);
    for (const s of stages) expect(s.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
    expect(container.querySelector('[aria-current="step"]')?.getAttribute('data-stage')).toBe('watching');
    expect(screen.getByText('Next change allowed at 5:13 PM IST')).toBeTruthy();
    // A stage without a line shows its status word, never nothing.
    expect(stages[0].textContent).toContain('Done');
  });

  it('a stage list with nothing current still carries an icon with its label', () => {
    const states = deriveLifecycle({ measured: true, hasSuggestion: false }).map((s) => ({ ...s, status: 'todo' as const, tone: 'off' as const }));
    const { container } = render(<StepIndicator states={states} label="Not sized: 1 replica" />);
    expect(screen.getByText('Not sized: 1 replica')).toBeTruthy();
    expect(container.querySelectorAll('svg[aria-hidden="true"]').length).toBe(1);
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
