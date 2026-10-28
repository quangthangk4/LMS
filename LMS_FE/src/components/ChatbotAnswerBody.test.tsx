import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatbotCards, ChatbotReplyView, safeChatPath } from '../../components/ChatbotAnswerBody';
import type { FaqAnswer, FaqCard } from '../../api/chatbotService';

test('repeated legacy tool prose is shown once without turning the answer into a source title', () => {
  const text = 'Có. Clean Code còn 6 bản tại CS1.';
  const result: FaqAnswer = { status: 'ANSWERED', answer: '', sections: [
    { status: 'ANSWERED', answer: text, sourceTitle: text, sourcePath: '/publicpage/book/1' },
    { status: 'ANSWERED', answer: text, sourceTitle: text, sourcePath: '/publicpage/book/1' },
  ] };
  render(<MemoryRouter><ChatbotReplyView answer={result} language="vi" /></MemoryRouter>);
  expect(screen.getAllByText(text)).toHaveLength(1);
  expect(screen.getByRole('link', { name: 'Xem nguồn' })).toHaveAttribute('href', '/publicpage/book/1');
  expect(screen.queryByRole('link', { name: /Xem hướng dẫn/ })).not.toBeInTheDocument();
});
test('copies and metadata for one publication share one compact card without mutating input', () => {
  const fact = (label: string, value: string) => Object.freeze({ label, value });
  const cards: FaqCard[] = [
    { id: 'p1', title: 'Clean Code', path: '/publicpage/book/1', facts: [fact('ISBN', '123')] },
    { id: 'i1', title: 'Clean Code', path: '/publicpage/book/1', facts: [fact('Barcode', 'B1'), fact('Cơ sở', 'CS1'), fact('Vị trí kệ', 'A1')] },
    { id: 'i2', title: 'Clean Code', path: '/publicpage/book/1', facts: [fact('Barcode', 'B2'), fact('Cơ sở', 'CS1'), fact('Vị trí kệ', 'A2')] },
  ];
  render(<MemoryRouter><ChatbotCards cards={cards} language="vi" /></MemoryRouter>);
  expect(screen.getAllByRole('article')).toHaveLength(1);
  expect(screen.getAllByText('Clean Code')).toHaveLength(1);
  expect(screen.getByText('B1 · B2').closest('details')).not.toHaveAttribute('open');
  expect(cards[1].facts[0].value).toBe('B1');
});
test('loan cards prioritize status and a readable due date, with other fields collapsed', () => {
  render(<MemoryRouter><ChatbotCards language="vi" cards={[{ id: 'loan', title: 'Deep Learning', path: '/userpage/my-books', facts: [
    { label: 'Barcode', value: 'BK-DLG-001' }, { label: 'Trạng thái', value: 'Quá hạn' },
    { label: 'Hạn trả', value: '2026-10-13' }, { label: 'Ngày trả', value: '—' },
  ] }]} /></MemoryRouter>);
  expect(screen.getByText('13/10/2026')).toBeVisible();
  expect(screen.getByText('Quá hạn')).toBeVisible();
  expect(screen.queryByText('Ngày trả')).not.toBeInTheDocument();
  expect(screen.getByText('BK-DLG-001').closest('details')).not.toHaveAttribute('open');
});
test('shows three books first and allows expanding the remaining matches', () => {
  const cards = Array.from({ length: 5 }, (_, i) => ({ id: String(i + 1), title: `Book ${i + 1}`, path: `/publicpage/book/${i + 1}`, facts: [] }));
  render(<MemoryRouter><ChatbotCards language="vi" cards={cards} /></MemoryRouter>);
  expect(screen.queryByText('Book 4')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Xem thêm 2 sách' }));
  expect(screen.getByText('Book 5')).toBeVisible();
});
test('exact help anchors are accepted but external paths and malformed fragments are rejected', () => {
  expect(safeChatPath('/publicpage/faq#password')).toBe(true);
  expect(safeChatPath('/publicpage/guide#borrow')).toBe(true);
  expect(safeChatPath('/publicpage/faq#password?evil=1')).toBe(false);
  expect(safeChatPath('https://evil.example/faq#password')).toBe(false);
  expect(safeChatPath('/publicpage/faq#<script>')).toBe(false);
});
