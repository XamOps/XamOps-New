import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { SampleCell, SampleValue, formatSampleForDisplay } from '../SampleValue';
describe('SampleValue', () => {
  it('renders an angle-bracketed sample half-masked and literally, creating no element', () => {
    const { container } = render(<SampleValue value="<<Jane Doe>>" />);
    expect(container.textContent).toBe('<<Ja•• D••>>');
    expect(container.querySelectorAll('*')).toHaveLength(1);   // the one <span>
  });
  it('masks a sample (half mask)', () => {
    expect(formatSampleForDisplay('pat@example.com')).toBe('p••@exa••••.c••');
  });
  it('renders a server-masked value unchanged', () => {
    const { container } = render(<SampleValue value="<<Ja•• D••>>" />);
    expect(container.textContent).toBe('<<Ja•• D••>>');
  });
});
describe('SampleCell', () => {
  it('shows the first of five samples masked and "+4"', () => {
    const { container } = render(<SampleCell samples={['<<Jane Doe>>', 'b', 'c', 'd', 'e']} />);
    expect(container.textContent).toBe('<<Ja•• D••>>+4');
    expect(container.querySelector('.font-mono')?.textContent).toBe('<<Ja•• D••>>');
  });
  it('shows one sample masked without a badge', () => {
    const { container } = render(<SampleCell samples={['pat@example.com']} />);
    expect(container.textContent).toBe('p••@exa••••.c••');
  });
  it('shows "-" when there is no sample', () => {
    const { container } = render(<SampleCell samples={[]} />);
    expect(container.textContent).toBe('-');
  });
});
