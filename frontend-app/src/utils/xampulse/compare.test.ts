import { describe, expect, it } from 'vitest';
import { compareLabel, compareOffsetSec, parseCompare } from './compare';
import { withExtras } from '../../pages/xampulse/HostPage';
import type { ChartSeries } from '../../components/XamPulse/charts/TimeSeriesChart';

const H = 3_600_000;

describe('compare with', () => {
  it('turns a mode into how far back to look', () => {
    expect(compareOffsetSec('off', 0, H)).toBeUndefined();
    expect(compareOffsetSec('prev', 0, 6 * H)).toBe(6 * 3600);
    expect(compareOffsetSec('day', 0, H)).toBe(86_400);
    expect(compareOffsetSec('week', 0, 30 * 24 * H)).toBe(604_800);
  });
  it('names the dashed line in plain words and reads old links', () => {
    expect(compareLabel('week')).toBe('last week');
    expect(compareLabel('off')).toBeNull();
    expect(parseCompare('1')).toBe('prev');
    expect(parseCompare(null)).toBe('off');
  });
});

describe('charts with a normal range and a comparison', () => {
  const cpu: ChartSeries = { key: 'system.cpu.utilization', name: 'Total', points: [[1000, 40]] };
  const steal: ChartSeries = { key: 'system.cpu.utilization{state=steal}', name: 'Steal', points: [[1000, 2]] };
  const bands = { 'system.cpu.utilization': [[1000, 30, 50]] as [number, number, number][] };
  const compare = { 'system.cpu.utilization': [[1000, 35]] as [number, number][] };

  it('shades the learned range and adds last week dashed in the same colour, only where they exist', () => {
    const out = withExtras([cpu, steal], { bands, compare, showBands: true, compareLabel: 'last week' });
    expect(out.map((s) => s.name)).toEqual(['Total', 'Total, last week', 'Steal']);
    expect(out[0].band).toEqual([[1000, 30, 50]]);
    expect(out[1]).toMatchObject({ dashed: true, color: out[0].color, points: [[1000, 35]] });
    expect(out[2].band).toBeUndefined();
    expect(out[2].color).not.toBe(out[0].color);
  });

  it('draws neither when the range is switched off and no comparison was asked for', () => {
    const out = withExtras([cpu], { bands, compare, showBands: false, compareLabel: null });
    expect(out).toHaveLength(1);
    expect(out[0].band).toBeUndefined();
  });

  it('converts both the way the series was converted (load per vCPU)', () => {
    const load: ChartSeries = { key: 'system.cpu.load_average.1m', name: '1 min', points: [[1000, 1]] };
    const out = withExtras([load], { bands: { 'system.cpu.load_average.1m': [[1000, 1, 3]] }, compare: { 'system.cpu.load_average.1m': [[1000, 4]] },
      showBands: true, compareLabel: 'yesterday' }, (v) => v / 2);
    expect(out[0].band).toEqual([[1000, 0.5, 1.5]]);
    expect(out[1].points).toEqual([[1000, 2]]);
  });
});
