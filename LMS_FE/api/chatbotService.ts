import axiosInstance from './axiosInstance';

export interface FaqSuggestion { id: string; question: string }
export interface FaqContent { summary: string; steps: string[]; bullets: string[]; note: string }
export interface FaqSource { title: string; path: string }
export interface FaqCard { id: string; title: string; subtitle?: string | null; imageUrl?: string | null; path: string; facts: { label: string; value: string }[] }
export interface FaqAnswer {
  status: 'ANSWERED' | 'UNSUPPORTED' | 'UNAVAILABLE' | 'AUTH_REQUIRED' | 'CLARIFY' | 'NO_MATCH';
  conversationId?: string;
  contextReset?: boolean;
  reasonCode?: string;
  sections?: FaqAnswer[];
  faqId?: string;
  answer: string;
  content?: FaqContent | null;
  sourceTitle?: string;
  sourcePath?: string;
  sources?: FaqSource[];
  cards?: FaqCard[];
  total?: number | null;
  page?: number | null;
  retrievedAt?: string | null;
}
interface Response<T> { code: number; data: T }

export const chatbotService = {
  suggestions: (language: 'vi' | 'en', signal?: AbortSignal): Promise<Response<FaqSuggestion[]>> =>
    axiosInstance.get('/chatbot/faqs', { params: { language }, signal }),
  ask: (message: string, language: 'vi' | 'en', signal?: AbortSignal, personal = false, conversationId?: string): Promise<Response<FaqAnswer>> =>
    axiosInstance.post(personal ? '/chatbot/my/messages' : '/chatbot/messages', { message, language, ...(conversationId ? { conversationId } : {}) }, { signal, timeout: 30000 }),
  clear: (conversationId: string, personal = false): Promise<void> =>
    axiosInstance.delete(`${personal ? '/chatbot/my' : '/chatbot'}/conversations/${encodeURIComponent(conversationId)}`),
};
