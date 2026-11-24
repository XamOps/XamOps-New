import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { marked, type TokensList } from 'marked';
import { MarkdownView } from '../MarkdownView';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MarkdownView', () => {
  it('shows raw HTML as text and creates no element from it', () => {
    const { container } = render(
      <MarkdownView markdown={'Run <script>alert(1)</script> against <bucket_name> today.\n\n<img src=x onerror=alert(1)>'} />,
    );
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('bucket_name')).toBeNull();
    expect(container).toHaveTextContent('Run <script>alert(1)</script> against <bucket_name> today.');
    expect(container).toHaveTextContent('<img src=x onerror=alert(1)>');
  });

  it('renders a javascript: link as its text only', () => {
    render(<MarkdownView markdown="[open the report](javascript:alert(1))" />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('open the report')).toBeInTheDocument();
  });

  it('opens an https link in a new tab without an opener', () => {
    render(<MarkdownView markdown="See [the guide](https://docs.example.com/guide)." />);
    const link = screen.getByRole('link', { name: 'the guide' });
    expect(link).toHaveAttribute('href', 'https://docs.example.com/guide');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('shows a fenced json block with its label and the markup inside it literally', () => {
    const { container } = render(<MarkdownView markdown={'```json\n{"tag": "<b>bold</b>"}\n```'} />);
    expect(screen.getByText('json')).toBeInTheDocument();
    expect(container.querySelector('pre code')?.textContent).toBe('{"tag": "<b>bold</b>"}');
    expect(container.querySelector('b')).toBeNull();
  });

  it('keeps inline code literal', () => {
    const { container } = render(<MarkdownView markdown="Replace `<SOURCE_BUCKET>` with the bucket name." />);
    expect(container.querySelector('code')?.textContent).toBe('<SOURCE_BUCKET>');
    expect(container.querySelector('source_bucket')).toBeNull();
  });

  it('copies a code block to the clipboard and expands it', async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    const { container } = render(<MarkdownView markdown={'```bash\naws s3 ls\n```'} />);

    await user.click(screen.getByRole('button', { name: 'Copy code' }));
    expect(writeText).toHaveBeenCalledWith('aws s3 ls');
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();

    const pre = container.querySelector('pre') as HTMLElement;
    expect(pre).toHaveClass('max-h-48');
    await user.click(screen.getByRole('button', { name: 'Expand code' }));
    expect(pre).not.toHaveClass('max-h-48');
    expect(screen.getByRole('button', { name: 'Collapse code' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('renders relative and anchor links as their text without throwing', () => {
    expect(() => render(<MarkdownView markdown="Go [back up](#top) or read [the notes](./notes.md)." />)).not.toThrow();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('Go back up or read the notes.')).toBeInTheDocument();
  });

  it('resolves a reference-style link through its definition and renders nothing for the definition', () => {
    const { container } = render(
      <MarkdownView markdown={'Read [the runbook][runbook] first.\n\n[runbook]: https://docs.example.com/runbook'} />,
    );
    expect(screen.getByRole('link', { name: 'the runbook' })).toHaveAttribute('href', 'https://docs.example.com/runbook');
    expect(container).not.toHaveTextContent('[runbook]:');
    expect(container.querySelectorAll('p')).toHaveLength(1);
  });

  it('renders a task list without checkbox markup', () => {
    const { container } = render(<MarkdownView markdown={'- [ ] rotate the key\n- [x] turn on logging'} />);
    expect(screen.getAllByRole('listitem').map(item => item.textContent)).toEqual(['rotate the key', 'turn on logging']);
    expect(container.querySelector('input')).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('renders the text of an unknown token type and nothing for one without text', () => {
    vi.spyOn(marked, 'lexer').mockReturnValueOnce([
      { type: 'paragraph', raw: 'x', text: 'x', tokens: [{ type: 'mention', raw: '@ops', text: '@ops-team' }] },
      { type: 'widget', raw: '[[widget]]' },
    ] as unknown as TokensList);
    const { container } = render(<MarkdownView markdown="anything" />);
    expect(screen.getByText('@ops-team')).toBeInTheDocument();
    expect(container).not.toHaveTextContent('widget');
  });

  it('decodes entities in prose only, within the code-point range', () => {
    const { container } = render(<MarkdownView markdown={'Tom &amp; Jerry, &#65;, &#x110000; and `a &amp; b`'} />);
    expect(container.querySelector('p')?.textContent).toBe('Tom & Jerry, A, &#x110000; and a &amp; b');
    expect(container.querySelector('code')?.textContent).toBe('a &amp; b');
  });

  it('renders nothing for an empty or missing text', () => {
    const { container } = render(<><MarkdownView markdown={null} /><MarkdownView markdown="" /></>);
    expect(container).toBeEmptyDOMElement();
  });
});
