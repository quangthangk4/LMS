import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatbotMarkdown } from '../../components/ChatbotMarkdown';

test('renders the model text as paragraphs, emphasis, lists and exact topic links without cards', () => {
  render(<MemoryRouter><ChatbotMarkdown text={'Có **2 cuốn** cần trả:\n\n1. Deep Learning — **13/10/2026**.\n2. Java Basics — 16/10/2026.\n\n[Xem hướng dẫn](/publicpage/faq#password)'} /></MemoryRouter>);
  expect(screen.getByText('2 cuốn').tagName).toBe('STRONG');
  expect(screen.getAllByRole('listitem')).toHaveLength(2);
  expect(screen.getByRole('link', { name: 'Xem hướng dẫn' })).toHaveAttribute('href','/publicpage/faq#password');
  expect(screen.queryByRole('article')).not.toBeInTheDocument();
});
test('renders tables when the model chooses them and preserves book links for later context', () => {
  render(<MemoryRouter><ChatbotMarkdown text={'| Sách | Cơ sở |\n| --- | --- |\n| [Java](/publicpage/book/42) | CS2 |'} /></MemoryRouter>);
  expect(screen.getByRole('table')).toBeInTheDocument();
  expect(screen.getByRole('link', {name:'Java'})).toHaveAttribute('href','/publicpage/book/42');
});
test('does not execute model HTML or allow javascript URLs', () => {
  const {container}=render(<MemoryRouter><ChatbotMarkdown text={'Nội dung an toàn.\n\n<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[Unsafe](javascript:alert(1))'} /></MemoryRouter>);
  expect(container.querySelector('script')).toBeNull();expect(container.querySelector('[onerror]')).toBeNull();
  expect(screen.getByText('Nội dung an toàn.')).toBeVisible();
  expect(screen.getByText('Unsafe').closest('a')?.getAttribute('href')??'').not.toMatch(/^javascript:/);
});
test('external links have safe new-tab attributes', () => {
  render(<MemoryRouter><ChatbotMarkdown text={'[Nguồn](https://example.com)'} /></MemoryRouter>);
  expect(screen.getByRole('link')).toHaveAttribute('rel','noopener noreferrer');
  expect(screen.getByRole('link')).toHaveAttribute('target','_blank');
});
