import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import UpdateStackBanner from '../UpdateStackBanner';
import type { StackCapabilities } from '../../../../api/autospottingApi';

const old: StackCapabilities = {
  applicable: true,
  templateVersion: 'rw-2026.10.1',
  latestVersion: 'rw-2026.11.1',
  updateRequired: true,
  missing: [{ code: 'AS_EVENTS_MULTI_REGION', label: 'events from every region' }],
};

describe('UpdateStackBanner', () => {
  it('names the missing capability and the version it moves to', () => {
    render(<UpdateStackBanner capabilities={old} onUpdate={() => {}} consoleHref="/account-manager" />);
    expect(screen.getByRole('status', { name: 'Update stack' })).toHaveTextContent('events from every region');
    expect(screen.getByRole('status')).toHaveTextContent('rw-2026.10.1');
    expect(screen.getByRole('status')).toHaveTextContent('rw-2026.11.1');
  });

  it('offers the change set path and the console path', () => {
    const onUpdate = vi.fn();
    render(<UpdateStackBanner capabilities={old} onUpdate={onUpdate} consoleHref="/account-manager" />);
    fireEvent.click(screen.getByRole('button', { name: 'Update stack' }));
    expect(onUpdate).toHaveBeenCalledOnce();
    expect(screen.getByRole('link', { name: 'Update in the AWS console' })).toHaveAttribute('href', '/account-manager');
  });

  it('says the version is not recorded when the account has none', () => {
    render(<UpdateStackBanner capabilities={{ ...old, templateVersion: null }} onUpdate={() => {}} consoleHref="/x" />);
    expect(screen.getByRole('status')).toHaveTextContent('not recorded');
  });

  it('renders nothing for a current stack, a read-only connection or no data', () => {
    const { container, rerender } = render(
      <UpdateStackBanner capabilities={{ ...old, updateRequired: false, missing: [] }} onUpdate={() => {}} consoleHref="/x" />,
    );
    expect(container).toBeEmptyDOMElement();
    rerender(<UpdateStackBanner capabilities={{ ...old, applicable: false }} onUpdate={() => {}} consoleHref="/x" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<UpdateStackBanner capabilities={undefined} onUpdate={() => {}} consoleHref="/x" />);
    expect(container).toBeEmptyDOMElement();
  });
});
