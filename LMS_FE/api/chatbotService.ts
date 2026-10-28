import axiosInstance from './axiosInstance';

export interface FaqSuggestion { id: string; question: string }
export interface FaqContent { summary: string; steps: string[]; bullets: string[]; note: string }
export interface ChatbotAnswer {
  status: 'ANSWERED' | 'UNAVAILABLE';
  format: 'markdown';
  conversationId?: string;
  contextReset?: boolean;
  reasonCode?: string;
  answer: string;
}
interface Response<T> { code: number; data: T }

export const chatbotService = {
  suggestions: (language: 'vi' | 'en', signal?: AbortSignal): Promise<Response<FaqSuggestion[]>> =>
    axiosInstance.get('/chatbot/faqs', { params: { language }, signal }),
  ask: (message: string, language: 'vi' | 'en', signal?: AbortSignal, personal = false, conversationId?: string): Promise<Response<ChatbotAnswer>> =>
    axiosInstance.post(personal ? '/chatbot/my/messages' : '/chatbot/messages', { message, language, ...(conversationId ? { conversationId } : {}) }, { signal, timeout: 90000 }),
  clear: (conversationId: string, personal = false): Promise<void> =>
    axiosInstance.delete(`${personal ? '/chatbot/my' : '/chatbot'}/conversations/${encodeURIComponent(conversationId)}`),
};
