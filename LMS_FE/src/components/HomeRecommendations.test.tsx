import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HomePage from '../../pages/public_pages/HomePage';
import recommendations from '../../api/recommendationService';

let mockSessionVersion = 1;
let mockUserType: string | null = 'student';
jest.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ userType: mockUserType, sessionVersion: mockSessionVersion }) }));
jest.mock('../../contexts/LanguageContext', () => ({ useTranslation: () => ({ language: 'vi', t: (key: string) => key }) }));
jest.mock('../../api/recommendationService', () => ({ __esModule: true, default: { getRecommendations: jest.fn(), refreshRecommendations: jest.fn() } }));
jest.mock('../../api/publicationsService', () => ({ __esModule: true, default: {
  getNewestPublications: jest.fn().mockResolvedValue({ code: 200, data: [] }),
  getMostBorrowedPublications: jest.fn().mockResolvedValue({ code: 200, data: [] }),
  getPublicStats: jest.fn().mockResolvedValue({ code: 200, data: null }),
} }));
jest.mock('../../api/categoriesService', () => ({ __esModule: true, default: { getAllCategories: jest.fn().mockResolvedValue({ code: 200, data: [] }) } }));
jest.mock('../../api/searchHistoryService', () => ({ __esModule: true, default: { getHistory: jest.fn().mockResolvedValue({ data: [] }) } }));
jest.mock('../../api/systemReviewsService', () => ({ __esModule: true, default: {
  getTopReviews: jest.fn().mockResolvedValue({ code: 200, data: [] }),
  getSummary: jest.fn().mockResolvedValue({ code: 200, data: null }),
  getMyReview: jest.fn().mockResolvedValue({ data: null }),
} }));
const book = (title: string) => ({ publicationId: '1', title, authorNames: ['Author'], availableItems: 2, ratingAverage: 4, ratingCount: 1, borrowCount: 3, coverImageUrl: null, publicationYear: 2026 });
const view = () => render(<MemoryRouter><HomePage /></MemoryRouter>);
beforeEach(() => {
  jest.clearAllMocks(); mockSessionVersion = 1; mockUserType = 'student';
  jest.mocked(recommendations.getRecommendations).mockResolvedValue({ code: 200, message: 'OK', data: [book('Current book')] });
});
test('explicit refresh keeps cards visible, prevents duplicate clicks and replaces them on success', async () => {
  let finish!: (result: any) => void;
  jest.mocked(recommendations.refreshRecommendations).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  view(); await screen.findByText('Current book');
  fireEvent.click(screen.getByRole('button', { name: 'Cập nhật cá nhân hóa' }));
  expect(screen.getByText('Current book')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Đang cập nhật…' })).toBeDisabled();
  expect(recommendations.refreshRecommendations).toHaveBeenCalledTimes(1);
  await act(async () => finish({ code: 200, data: [book('Refreshed book')] }));
  expect(await screen.findByText('Refreshed book')).toBeInTheDocument();
  expect(screen.queryByText('Current book')).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Đã cập nhật');
});
test('failed refresh preserves the list and shows an error instead of success', async () => {
  jest.mocked(recommendations.refreshRecommendations).mockRejectedValue(new Error('offline'));
  view(); await screen.findByText('Current book');
  fireEvent.click(screen.getByRole('button', { name: 'Cập nhật cá nhân hóa' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Danh sách hiện tại được giữ lại');
  expect(screen.getByText('Current book')).toBeInTheDocument();
});
test('a late response from a previous account cannot replace the next account recommendations', async () => {
  let finish!: (result: any) => void;
  jest.mocked(recommendations.refreshRecommendations).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const rendered = view(); await screen.findByText('Current book');
  fireEvent.click(screen.getByRole('button', { name: 'Cập nhật cá nhân hóa' }));
  mockSessionVersion++;
  jest.mocked(recommendations.getRecommendations).mockResolvedValue({ code: 200, message: 'OK', data: [book('Next account')] });
  rendered.rerender(<MemoryRouter><HomePage /></MemoryRouter>);
  await screen.findByText('Next account');
  await act(async () => finish({ code: 200, data: [book('Wrong account')] }));
  expect(screen.queryByText('Wrong account')).not.toBeInTheDocument();
  expect(screen.getByText('Next account')).toBeInTheDocument();
});
test('anonymous visitors do not see a personalization refresh action', async () => {
  mockUserType = null; view();
  await waitFor(() => expect(screen.getByText('home.loginForRecommendations')).toBeInTheDocument());
  expect(screen.queryByRole('button', { name: 'Cập nhật cá nhân hóa' })).not.toBeInTheDocument();
  expect(recommendations.getRecommendations).not.toHaveBeenCalled();
});
