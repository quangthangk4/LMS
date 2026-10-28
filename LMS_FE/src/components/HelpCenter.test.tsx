import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HelpCenter from '../../components/HelpCenter';
import { helpService } from '../../api/helpService';
import policyService from '../../api/circulationPolicyService';

let mockLanguage: 'vi' | 'en' = 'vi';
jest.mock('../../contexts/LanguageContext', () => ({ useLanguage: () => ({ language: mockLanguage }) }));
jest.mock('../../api/helpService', () => ({ helpService: { content: jest.fn() } }));
jest.mock('../../api/circulationPolicyService', () => ({ __esModule: true, default: { getPolicy: jest.fn() } }));
const load = helpService.content as jest.Mock;
const policy = policyService.getPolicy as jest.Mock;
const topic = { id: 'borrow', question: 'Cách mượn sách?', sourcePath: '/publicpage/guide',
  content: { summary: 'Nguồn dùng chung.', steps: ['Đăng nhập.', 'Chọn sách.'], bullets: [], note: 'Nhận sách tại quầy.' } };
beforeEach(() => {
  jest.clearAllMocks(); mockLanguage = 'vi';
  load.mockResolvedValue({ data: { revision: 1, version: 'v1', topics: [topic] } });
  policy.mockResolvedValue({ data: { defaultLoanDays: 21, maxRenewals: 3, defaultDepositAmount: 50000, overdueFinePerDay: 2000 } });
});
test('guide renders canonical content and searches locally without sending requests per character', async () => {
  render(<MemoryRouter><HelpCenter kind="guide" /></MemoryRouter>);
  expect(await screen.findByText('Nguồn dùng chung.')).toBeInTheDocument();
  fireEvent.click(screen.getByText('Cách mượn sách?'));
  expect(screen.getByText('Đăng nhập.').closest('ol')).toBeInTheDocument();
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'không tìm thấy' } });
  expect(screen.getByRole('status')).toHaveTextContent('Chưa tìm thấy');
  expect(load).toHaveBeenCalledTimes(1); expect(policy).not.toHaveBeenCalled();
});
test('FAQ displays current policy without hardcoded defaults and allows a content retry', async () => {
  load.mockRejectedValueOnce(new Error('Unavailable'));
  render(<MemoryRouter><HelpCenter kind="faq" /></MemoryRouter>);
  expect(await screen.findByText('21')).toBeInTheDocument();
  expect(screen.getByText('50.000đ')).toBeInTheDocument();
  expect(await screen.findByRole('alert')).toHaveTextContent('Chưa tải được');
  fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
  expect(await screen.findByText('Nguồn dùng chung.')).toBeInTheDocument();
});
test('policy errors do not display invented default days, fines or deposits', async () => {
  policy.mockRejectedValue(new Error('Unavailable'));
  render(<MemoryRouter><HelpCenter kind="faq" /></MemoryRouter>);
  expect(await screen.findByRole('alert')).toBeInTheDocument();
  expect(screen.queryByText('14')).not.toBeInTheDocument();
  expect(screen.queryByText('0đ')).not.toBeInTheDocument();
});
test('language changes abort old content and load the selected language', async () => {
  load.mockReturnValue(new Promise(() => {}));
  const result = render(<MemoryRouter><HelpCenter kind="guide" /></MemoryRouter>);
  const signal = load.mock.calls[0][1] as AbortSignal;
  mockLanguage = 'en';
  load.mockResolvedValue({ data: { topics: [{ ...topic, question: 'How do I borrow?' }] } });
  result.rerender(<MemoryRouter><HelpCenter kind="guide" /></MemoryRouter>);
  expect(signal.aborted).toBe(true);
  expect(await screen.findByText('How do I borrow?')).toBeInTheDocument();
  await waitFor(() => expect(load).toHaveBeenLastCalledWith('en', expect.any(AbortSignal)));
});

test('a direct FAQ anchor opens and focuses the exact topic after content finishes loading', async () => {
  load.mockResolvedValue({ data: { topics: [topic, { ...topic, id: 'password', question: 'Tôi quên mật khẩu thì làm gì?' }] } });
  render(<MemoryRouter initialEntries={['/publicpage/faq#password']}><HelpCenter kind="faq" /></MemoryRouter>);
  await screen.findByText('Tôi quên mật khẩu thì làm gì?');
  const target = document.getElementById('password');
  expect(target).toHaveAttribute('open');
  expect(target).toHaveFocus();
  expect(document.getElementById('borrow')).not.toHaveAttribute('open');
});
