import { describe, it, expect } from 'vitest';
import {
  MAX_COST_IDS,
  MAX_COST_IDS_CHARS,
  MAX_COST_ID_CHARS,
  trimCostIds,
} from '../../../api/curInsightsApi';

/**
 * The client half of the id limits, which is a pure function and therefore cheap to pin.
 *
 * <p>Worth pinning separately from the tab: these three numbers are a copy of the backend's, and a
 * disagreement between the two copies is invisible until someone opens a resource with 150
 * connections and gets a 400 about a request they did not make.
 */
describe('trimCostIds', () => {
  it('sends the focus first and the peers in the order they arrived', () => {
    const { sent, sentPeerCount, totalPeerCount } = trimCostIds('vol-0aa1', ['i-1', 'i-2']);

    // Model order in, model order out: the trim drops from the end of the order the model
    // considers least informative (inferred relations, alphabetically last), so reordering here
    // would change which connections lose their figures.
    expect(sent).toEqual(['vol-0aa1', 'i-1', 'i-2']);
    expect(sentPeerCount).toBe(2);
    expect(totalPeerCount).toBe(2);
  });

  it('caps the count including the focus', () => {
    const peers = Array.from({ length: 150 }, (_, i) => `i-${i}`);

    const { sent, sentPeerCount, totalPeerCount } = trimCostIds('vol-0aa1', peers);

    expect(sent).toHaveLength(MAX_COST_IDS);
    expect(sent[0]).toBe('vol-0aa1');
    expect(sentPeerCount).toBe(MAX_COST_IDS - 1);
    // Counted so the tab can say "costs shown for the first 99 of 150 connections" in peers rather
    // than in ids, which is the number the reader can see.
    expect(totalPeerCount).toBe(150);
  });

  it('does not let a duplicate consume a slot', () => {
    const { sent } = trimCostIds('i-1', ['i-1', 'i-2']);

    expect(sent).toEqual(['i-1', 'i-2']);
  });

  it('drops an id longer than the widest inventory identifier column', () => {
    const tooLong = 'x'.repeat(MAX_COST_ID_CHARS + 1);

    const { sent, sentPeerCount, totalPeerCount } = trimCostIds('vol-0aa1', [tooLong, 'i-1']);

    // It cannot price under any circumstances, so dropping it costs nothing and buys room for one
    // that can.
    expect(sent).toEqual(['vol-0aa1', 'i-1']);
    expect(sentPeerCount).toBe(1);
    expect(totalPeerCount).toBe(2);
  });

  it('trims for total length from the end, never the focus', () => {
    // Long enough that the character budget binds before the count does, which is the normal case
    // for ARN-shaped ids.
    const peers = Array.from({ length: 60 }, (_, i) =>
      `arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc${String(i).padStart(12, '0')}`);

    const { sent, sentPeerCount } = trimCostIds('vol-0aa1122334455667', peers);

    expect(sent.join(',').length).toBeLessThanOrEqual(MAX_COST_IDS_CHARS);
    // The focus survives all three rules: it is the one figure the tab must always be able to
    // show, and its absence would read as "this resource has no cost" rather than as a trim.
    expect(sent[0]).toBe('vol-0aa1122334455667');
    expect(sentPeerCount).toBeLessThan(peers.length);
    expect(sent.slice(1)).toEqual(peers.slice(0, sentPeerCount));
  });

  it('produces a parameter the backend\'s own rules would accept', () => {
    // The cheapest way to notice the two copies of the limits drifting apart: measure the output
    // the way parseIds measures its input — raw parameter length first, then the count, then each
    // id's length.
    const peers = Array.from({ length: 300 }, (_, i) =>
      `arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc${String(i).padStart(12, '0')}`);

    const { sent } = trimCostIds('vol-0aa1122334455667', peers);
    const parameter = sent.join(',');

    expect(parameter.length).toBeLessThanOrEqual(MAX_COST_IDS_CHARS);
    expect(sent.length).toBeLessThanOrEqual(MAX_COST_IDS);
    expect(sent.every((id) => id.length <= MAX_COST_ID_CHARS)).toBe(true);
    expect(parameter.split(',').every((id) => id.trim().length > 0)).toBe(true);
  });

  it('sends only the focus when there are no peers', () => {
    const { sent, sentPeerCount, totalPeerCount } = trimCostIds('vol-0aa1', []);

    expect(sent).toEqual(['vol-0aa1']);
    expect(sentPeerCount).toBe(0);
    expect(totalPeerCount).toBe(0);
  });
});
