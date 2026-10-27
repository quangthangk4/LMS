import axios from '../../api/axiosInstance';
import service from '../../api/recommendationService';
jest.mock('../../api/axiosInstance', () => ({ __esModule: true, default: { get: jest.fn(), post: jest.fn() } }));
test('explicit recommendation refresh uses POST and does not send a user identity', () => {
  service.refreshRecommendations(10);
  expect(axios.post).toHaveBeenCalledWith('/recommendations/refresh', null, { params: { limit: 10 } });
});
