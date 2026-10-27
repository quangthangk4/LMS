import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import FaqChatbot from '../../components/FaqChatbot';
import { chatbotService } from '../../api/chatbotService';

let mockLanguage: 'vi' | 'en' = 'vi';
let mockUploads: unknown[] = [];
let mockUserType: string | null = null;
let mockSessionVersion = 0;
jest.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ userType: mockUserType, sessionVersion: mockSessionVersion }) }));
jest.mock('../../contexts/LanguageContext', () => ({ useLanguage: () => ({ language: mockLanguage }) }));
jest.mock('../../contexts/UploadContext', () => ({ useUpload: () => ({ uploads: mockUploads }) }));
jest.mock('../../api/chatbotService', () => ({ chatbotService: { suggestions: jest.fn(), ask: jest.fn(), clear: jest.fn().mockResolvedValue(undefined) } }));
const suggestions = chatbotService.suggestions as jest.Mock;
const ask = chatbotService.ask as jest.Mock;

test('follow-up reuses conversation context; clear starts a new context and invalidates the old one', async () => {
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Clean Code ở CS2 còn không?' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  await screen.findByText('Hướng dẫn đã duyệt.');
  const oldId = ask.mock.calls[0][4];
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Còn CS1?' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));
  expect(ask.mock.calls[1][4]).toBe(oldId);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Gửi câu hỏi' })).toBeDisabled());
  fireEvent.click(screen.getByRole('button', { name: 'Xóa hội thoại' }));
  expect(chatbotService.clear).toHaveBeenCalledWith(oldId, false);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Xin chào' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  await waitFor(() => expect(ask).toHaveBeenCalledTimes(3));
  expect(ask.mock.calls[2][4]).not.toBe(oldId);
});

test('multiple tool sections retain separate prose, cards and reference links', async () => {
  ask.mockResolvedValue({ data: { status: 'ANSWERED', answer: '', sections: [
    { status: 'ANSWERED', answer: 'Hướng dẫn lấy lại mật khẩu', content: { summary: 'Khôi phục mật khẩu', steps: ['Chọn Quên mật khẩu'], bullets: [], note: '' }, sources: [{ title: 'Hướng dẫn', path: '/publicpage/faq' }] },
    { status: 'ANSWERED', answer: 'Có. Clean Code còn 2 bản ở CS2.', cards: [{ id: '1', title: 'Clean Code', path: '/publicpage/book/1', facts: [{ label: 'Kệ', value: 'A1' }] }] },
  ] } });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Tôi quên mật khẩu, tìm sách Clean Code ở CS2' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Khôi phục mật khẩu')).toBeInTheDocument();
  expect(screen.getByText('Có. Clean Code còn 2 bản ở CS2.')).toBeInTheDocument();
  expect(screen.getByText('A1')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Hướng dẫn' })).toHaveAttribute('href', '/publicpage/faq');
});

function view() { return render(<MemoryRouter><FaqChatbot /></MemoryRouter>); }
async function open() {
  await userEvent.click(screen.getByRole('button', { name: 'Mở trợ lý hướng dẫn' }));
  await screen.findByRole('combobox', { name: 'Chọn câu hỏi gợi ý' });
}

beforeEach(() => {
  jest.clearAllMocks(); mockLanguage = 'vi'; mockUploads = []; mockUserType = null; mockSessionVersion = 0;
  suggestions.mockResolvedValue({ data: [{ id: 'borrow', question: 'Làm cách nào để mượn sách?' }] });
  ask.mockResolvedValue({ data: { status: 'ANSWERED', faqId: 'borrow', answer: 'Hướng dẫn đã duyệt.', sourceTitle: 'Mượn sách', sourcePath: '/publicpage/guide' } });
});

test('opens on demand without removed notices and focuses the question without an AI request', async () => {
  view(); expect(suggestions).not.toHaveBeenCalled();
  await open();
  expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'false');
  expect(screen.queryByText(/Chỉ hướng dẫn sử dụng/)).not.toBeInTheDocument();
  expect(screen.queryByText('Chỉ dùng câu trả lời đã duyệt')).not.toBeInTheDocument();
  expect(screen.getByRole('dialog')).not.toHaveAttribute('aria-describedby');
  expect(screen.getByRole('textbox')).toHaveFocus();
  expect(ask).not.toHaveBeenCalled();
});

test('renders a structured answer as separate paragraphs, ordered steps, bullets and note', async () => {
  ask.mockResolvedValue({ data: { status: 'ANSWERED', answer: 'Legacy text', content: {
    summary: 'Bạn có thể gửi yêu cầu mượn trên website.', steps: ['Đăng nhập.', 'Tìm sách và gửi yêu cầu.'],
    bullets: ['Nhận sách tại quầy.'], note: 'Kiểm tra thông báo nhận sách.',
  } } });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Hướng dẫn mượn' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Bạn có thể gửi yêu cầu mượn trên website.')).toBeInTheDocument();
  expect(screen.getByText('Đăng nhập.').closest('ol')).toBeInTheDocument();
  expect(screen.getByText('Nhận sách tại quầy.').closest('ul')).toBeInTheDocument();
  expect(screen.getByText('Kiểm tra thông báo nhận sách.')).toBeInTheDocument();
  expect(screen.queryByText('Legacy text')).not.toBeInTheDocument();
});

test('malformed structured content safely uses the compatible answer field', async () => {
  ask.mockResolvedValue({ data: { answer: 'Fallback instructions', content: { summary: 'Invalid', steps: null } } });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Fallback instructions')).toBeInTheDocument();
});

test('structured text is escaped and never rendered as generated HTML', async () => {
  ask.mockResolvedValue({ data: { answer: 'Text', content: {
    summary: '<img src=x onerror=alert(1)>', steps: [], bullets: [], note: '',
  } } });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});

test('typing does not send, Enter sends only current message/language and displays reviewed source', async () => {
  const user = userEvent.setup(); view(); await open();
  await user.type(screen.getByRole('textbox'), 'Cách mượn sách?');
  expect(ask).not.toHaveBeenCalled();
  await user.keyboard('{Enter}');
  expect(await screen.findByText('Hướng dẫn đã duyệt.')).toBeInTheDocument();
  expect(ask).toHaveBeenCalledTimes(1);
  expect(ask).toHaveBeenCalledWith('Cách mượn sách?', 'vi', expect.any(AbortSignal), false, expect.any(String));
  expect(screen.getByRole('link', { name: /Xem hướng dẫn/ })).toHaveAttribute('href', '/publicpage/guide');
});

test('suggested questions work and Shift+Enter does not submit', async () => {
  const user = userEvent.setup(); view(); await open();
  await user.type(screen.getByRole('textbox'), 'Test{Shift>}{Enter}{/Shift}');
  expect(ask).not.toHaveBeenCalled();
  await user.selectOptions(screen.getByRole('combobox'), 'Làm cách nào để mượn sách?');
  expect(await screen.findByText('Hướng dẫn đã duyệt.')).toBeInTheDocument();
});

test('blocks empty questions and duplicate submissions while pending', async () => {
  ask.mockReturnValue(new Promise(() => {})); view(); await open();
  expect(screen.getByRole('button', { name: 'Gửi câu hỏi' })).toBeDisabled();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(ask).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('textbox')).toHaveAttribute('maxLength', '500');
});

test('Escape aborts pending request, restores launcher focus and ignores a late response', async () => {
  let resolve!: (value: unknown) => void;
  ask.mockImplementation(() => new Promise(done => { resolve = done; }));
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  const signal = ask.mock.calls[0][2] as AbortSignal;
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  expect(signal.aborted).toBe(true);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Mở trợ lý hướng dẫn' })).toHaveFocus();
  await act(async () => { resolve({ data: { answer: 'Stale result' } }); });
  await open(); expect(screen.queryByText('Stale result')).not.toBeInTheDocument();
});

test('clear aborts outstanding work and removes local conversation', async () => {
  ask.mockReturnValue(new Promise(() => {})); view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Private question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  const signal = ask.mock.calls[0][2] as AbortSignal;
  await userEvent.click(screen.getByRole('button', { name: 'Xóa hội thoại' }));
  expect(signal.aborted).toBe(true);
  expect(screen.queryByText('Private question')).not.toBeInTheDocument();
  expect(screen.getByRole('textbox')).toHaveFocus();
});

test('request error retains the question for retry and handles throttling', async () => {
  ask.mockRejectedValue({ response: { status: 429 } }); view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByRole('alert')).toHaveTextContent('một phút');
  expect(screen.getByRole('textbox')).toHaveValue('Question');
  expect(screen.getByRole('button', { name: 'Gửi câu hỏi' })).toBeEnabled();
});

test('unavailable answers are displayed without inventing a source', async () => {
  ask.mockResolvedValue({ data: { status: 'UNAVAILABLE', answer: 'Hãy chọn câu gợi ý.' } });
  view(); await open(); await userEvent.selectOptions(screen.getByRole('combobox'), 'Làm cách nào để mượn sách?');
  expect(await screen.findByText('Hãy chọn câu gợi ý.')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /Xem hướng dẫn/ })).not.toBeInTheDocument();
});

test('renders text safely and refuses an unapproved source URL', async () => {
  ask.mockResolvedValue({ data: { status: 'ANSWERED', answer: '<img src=x onerror=alert(1)>', sourceTitle: 'Unsafe', sourcePath: 'https://evil.example' } });
  const { container } = view(); await open();
  await userEvent.selectOptions(screen.getByRole('combobox'), 'Làm cách nào để mượn sách?');
  expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
  expect(container.querySelector('img')).toBeNull();
  expect(screen.queryByRole('link', { name: /Unsafe/ })).not.toBeInTheDocument();
});

test('suggestion failure leaves manual entry and the guide available', async () => {
  suggestions.mockRejectedValue(new Error('offline')); view();
  await userEvent.click(screen.getByRole('button', { name: 'Mở trợ lý hướng dẫn' }));
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Chưa tải được'));
  expect(screen.getByRole('textbox')).toBeEnabled();
  expect(screen.getByRole('link', { name: 'Mở hướng dẫn' })).toBeInTheDocument();
});

test('language change clears conversation and aborts pending requests', async () => {
  ask.mockReturnValue(new Promise(() => {})); const rendered = view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Question' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  const signal = ask.mock.calls[0][2] as AbortSignal;
  mockLanguage = 'en'; rendered.rerender(<MemoryRouter><FaqChatbot /></MemoryRouter>);
  expect(signal.aborted).toBe(true);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Open help assistant' }));
  expect(screen.getByRole('dialog')).toHaveAccessibleName('Library74 help assistant');
  expect(screen.queryByText('Question')).not.toBeInTheDocument();
  await waitFor(() => expect(suggestions).toHaveBeenLastCalledWith('en', expect.any(AbortSignal)));
});

test('reader chat uses the authenticated path and clears private cards on session changes', async () => {
  mockUserType = 'student';
  ask.mockResolvedValue({ data: { answer: 'Dữ liệu của bạn', cards: [{ id: '1', title: 'Private book', path: '/userpage/my-books',
    facts: [{ label: 'Hạn trả', value: '2026-11-01' }] }], total: 1, page: 0 } });
  const result = view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Sách tôi đang mượn' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Private book')).toBeInTheDocument();
  expect(ask).toHaveBeenCalledWith('Sách tôi đang mượn', 'vi', expect.any(AbortSignal), true, expect.any(String));
  const link = screen.getByRole('link', { name: 'Mở chi tiết ở tab mới' });
  expect(link).toHaveAttribute('href', '/userpage/my-books');
  expect(link).toHaveAttribute('target', '_blank');
  mockSessionVersion++;
  result.rerender(<MemoryRouter><FaqChatbot /></MemoryRouter>);
  expect(screen.queryByText('Private book')).not.toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

test('cards reject external navigation and executable image URLs', async () => {
  ask.mockResolvedValue({ data: { answer: 'Books', cards: [{ id: '1', title: 'Book', path: 'https://evil.example',
    imageUrl: 'javascript:alert(1)', facts: [] }] } });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Find a book' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Book')).toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Mở chi tiết ở tab mới' })).not.toBeInTheDocument();
});

test('an expired reader session clears previously displayed private results', async () => {
  mockUserType = 'student';
  ask.mockResolvedValueOnce({ data: { answer: 'Private account result' } }).mockRejectedValueOnce({ status: 401 });
  view(); await open();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'My loans' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByText('Private account result')).toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'My fines' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(await screen.findByRole('alert')).toHaveTextContent('đăng nhập lại');
  expect(screen.queryByText('Private account result')).not.toBeInTheDocument();
});

test('upload progress takes priority so the two floating panels do not overlap', () => {
  mockUploads = [{ id: 'uploading' }]; view();
  expect(screen.queryByRole('button', { name: 'Mở trợ lý hướng dẫn' })).not.toBeInTheDocument();
});
