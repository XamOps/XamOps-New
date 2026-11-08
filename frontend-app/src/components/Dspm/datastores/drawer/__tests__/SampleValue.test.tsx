import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { SampleCell, SampleValue, formatSampleForDisplay } from '../SampleValue';

describe('SampleValue', () => {
  it('renders an angle-bracketed sample literally, creating no element', () => {
    const { container } = render(<SampleValue value="<<Jane Doe>>" />);
    expect(container.textContent).toBe('<<Jane Doe>>');
    expect(container.querySelectorAll('*')).toHaveLength(1);   // the one <span>
  });

  it('formats a sample unchanged (no masking yet)', () => {
    expect(formatSampleForDisplay('pat@example.com')).toBe('pat@example.com');
  });
});

describe('SampleCell', () => {
  it('shows the first of five samples and "+4"', () => {
    const { container } = render(<SampleCell samples={['<<Jane Doe>>', 'b', 'c', 'd', 'e']} />);
    expect(container.textContent).toBe('<<Jane Doe>>+4');
    expect(container.querySelector('.font-mono')?.textContent).toBe('<<Jane Doe>>');
  });

  it('shows one sample without a badge', () => {
    const { container } = render(<SampleCell samples={['pat@example.com']} />);
    expect(container.textContent).toBe('pat@example.com');
  });

  it('shows "-" when there is no sample', () => {
    const { container } = render(<SampleCell samples={[]} />);
    expect(container.textContent).toBe('-');
  });
});
