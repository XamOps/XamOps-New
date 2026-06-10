/**
 * Unit tests for the ScriptCard component.
 *
 * Uses Vitest + React Testing Library. Covers metadata rendering, truncation,
 * cloud-provider overflow (+N more), metrics, favorite toggle (with click
 * isolation from the card), and card activation via click / keyboard.
 *
 * Requirements: REQ-2.5.1
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ScriptCard, { truncate } from './ScriptCard';
import type { DevOpsScript } from '../../types/devopsInTheBox';

function makeScript(overrides: Partial<DevOpsScript> = {}): DevOpsScript {
  return {
    id: 1,
    name: 'Provision AWS VPC',
    description: 'Creates a VPC with public and private subnets.',
    category: 'terraform',
    code: 'resource "aws_vpc" "main" {}',
    usageInstructions: 'Run terraform apply',
    version: '1.0.0',
    tags: ['vpc', 'networking'],
    cloudProviders: ['aws'],
    difficulty: 'intermediate',
    author: 'XamOps Team',
    authorId: 'team',
    estimatedTimeMinutes: 10,
    prerequisites: 'AWS account',
    relatedDocumentationUrl: '',
    viewCount: 100,
    downloadCount: 1234,
    copyCount: 50,
    favoriteCount: 12,
    averageRating: 4.5,
    ratingCount: 20,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-02T00:00:00Z',
    lastUsedAt: '2024-01-03T00:00:00Z',
    status: 'published',
    isUserContributed: false,
    isFeatured: false,
    usage: 'Run terraform apply',
    ...overrides,
  };
}

describe('truncate', () => {
  it('leaves short text unchanged', () => {
    expect(truncate('hello', 60)).toBe('hello');
  });

  it('truncates long text with an ellipsis', () => {
    const result = truncate('a'.repeat(80), 60);
    expect(result.length).toBe(61); // 60 chars + ellipsis
    expect(result.endsWith('…')).toBe(true);
  });

  it('handles empty/undefined input', () => {
    expect(truncate('', 10)).toBe('');
    expect(truncate(undefined as unknown as string, 10)).toBe('');
  });
});

describe('ScriptCard', () => {
  const onOpenDetail = vi.fn();
  const onToggleFavorite = vi.fn();

  beforeEach(() => {
    onOpenDetail.mockReset();
    onToggleFavorite.mockReset();
  });

  it('renders name, description and category', () => {
    render(<ScriptCard script={makeScript()} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    expect(screen.getByText('Provision AWS VPC')).toBeTruthy();
    expect(screen.getByText('Creates a VPC with public and private subnets.')).toBeTruthy();
    expect(screen.getByText('terraform')).toBeTruthy();
  });

  it('truncates a long name to 60 characters', () => {
    const longName = 'X'.repeat(100);
    render(<ScriptCard script={makeScript({ name: longName })} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    const heading = screen.getByRole('heading', { level: 3 });
    expect(heading.textContent).toBe(`${'X'.repeat(60)}…`);
  });

  it('shows up to 3 cloud providers and a +N more chip', () => {
    render(
      <ScriptCard
        script={makeScript({ cloudProviders: ['aws', 'azure', 'gcp', 'multi-cloud'] })}
        onOpenDetail={onOpenDetail}
        onToggleFavorite={onToggleFavorite}
      />
    );
    expect(screen.getByText('AWS')).toBeTruthy();
    expect(screen.getByText('Azure')).toBeTruthy();
    expect(screen.getByText('GCP')).toBeTruthy();
    expect(screen.getByText('+1 more')).toBeTruthy();
  });

  it('renders difficulty badge and rating with count', () => {
    render(<ScriptCard script={makeScript()} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    expect(screen.getByText('intermediate')).toBeTruthy();
    expect(screen.getByText('4.5')).toBeTruthy();
    expect(screen.getByText('(20)')).toBeTruthy();
  });

  it('formats large download counts compactly', () => {
    render(<ScriptCard script={makeScript({ downloadCount: 1234 })} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    expect(screen.getByText('1.2k')).toBeTruthy();
  });

  it('opens detail on card click', () => {
    render(<ScriptCard script={makeScript()} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    fireEvent.click(screen.getByRole('button', { name: /open details for/i }));
    expect(onOpenDetail).toHaveBeenCalledTimes(1);
  });

  it('opens detail on Enter key', () => {
    render(<ScriptCard script={makeScript()} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    fireEvent.keyDown(screen.getByRole('button', { name: /open details for/i }), { key: 'Enter' });
    expect(onOpenDetail).toHaveBeenCalledTimes(1);
  });

  it('toggles favorite without opening detail', () => {
    render(<ScriptCard script={makeScript()} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    fireEvent.click(screen.getByRole('button', { name: /add provision aws vpc to favorites/i }));
    expect(onToggleFavorite).toHaveBeenCalledTimes(1);
    expect(onOpenDetail).not.toHaveBeenCalled();
  });

  it('reflects favorited state via aria-pressed', () => {
    render(<ScriptCard script={makeScript({ isFavorited: true })} onOpenDetail={onOpenDetail} onToggleFavorite={onToggleFavorite} />);
    const favBtn = screen.getByRole('button', { name: /remove provision aws vpc from favorites/i });
    expect(favBtn.getAttribute('aria-pressed')).toBe('true');
  });
});
