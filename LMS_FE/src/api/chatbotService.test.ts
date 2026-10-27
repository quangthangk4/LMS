import axiosInstance from '../../api/axiosInstance';
import { chatbotService } from '../../api/chatbotService';
import { isPublicRequest } from '../../api/publicRequestPolicy';

jest.mock('../../api/axiosInstance', () => ({ __esModule: true, default: { get: jest.fn(), post: jest.fn(), delete: jest.fn() } }));

test('FAQ endpoints are public without opening other chatbot methods', () => {
  expect(isPublicRequest({ method: 'get', url: '/chatbot/faqs' })).toBe(true);
  expect(isPublicRequest({ method: 'post', url: '/chatbot/messages' })).toBe(true);
  expect(isPublicRequest({ method: 'post', url: 'https://library74.uk/api/v1/chatbot/messages' })).toBe(true);
  expect(isPublicRequest({ method: 'get', url: '/chatbot/messages' })).toBe(false);
  expect(isPublicRequest({ method: 'post', url: '/chatbot/faqs' })).toBe(false);
});

test('sends only current question and language, with cancellation and a bounded timeout', () => {
  const signal = new AbortController().signal;
  chatbotService.suggestions('vi', signal);
  expect(axiosInstance.get).toHaveBeenCalledWith('/chatbot/faqs', { params: { language: 'vi' }, signal });
  chatbotService.ask('How do I borrow?', 'en', signal);
  expect(axiosInstance.post).toHaveBeenCalledWith('/chatbot/messages', { message: 'How do I borrow?', language: 'en' }, { signal, timeout: 30000 });
});

test('personal messages use an authenticated route and never accept an identity argument', () => {
  expect(isPublicRequest({ method: 'post', url: '/chatbot/my/messages' })).toBe(false);
  expect(isPublicRequest({ method: 'get', url: '/help/content' })).toBe(true);
  chatbotService.ask('My fines', 'en', undefined, true);
  expect(axiosInstance.post).toHaveBeenCalledWith('/chatbot/my/messages', { message: 'My fines', language: 'en' }, { signal: undefined, timeout: 30000 });
});

test('conversation IDs are sent without client history and clearing respects authenticated scope', () => {
  const id = 'b7350e6e-6719-4ea3-990c-cf6260a0292b';
  chatbotService.ask('What about campus 2?', 'en', undefined, true, id);
  expect(axiosInstance.post).toHaveBeenCalledWith('/chatbot/my/messages', { message: 'What about campus 2?', language: 'en', conversationId: id }, expect.any(Object));
  chatbotService.clear(id, true);
  expect(axiosInstance.delete).toHaveBeenCalledWith(`/chatbot/my/conversations/${id}`);
  expect(isPublicRequest({ method: 'delete', url: `/chatbot/conversations/${id}` })).toBe(true);
  expect(isPublicRequest({ method: 'delete', url: `/chatbot/my/conversations/${id}` })).toBe(false);
});
