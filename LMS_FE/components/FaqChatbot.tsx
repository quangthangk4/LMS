import { FormEvent, useEffect, useRef, useState } from 'react';
import { BookOpen, Loader2, MessageCircle, Send, Trash2, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useLanguage } from '../contexts/LanguageContext';
import { useUpload } from '../contexts/UploadContext';
import { useAuth } from '../contexts/AuthContext';
import { ChatbotAnswerBody, ChatbotCards, safeChatPath } from './ChatbotAnswerBody';
import { chatbotService, FaqAnswer, FaqContent, FaqSuggestion, FaqCard, FaqSource } from '../api/chatbotService';

type Message = { role: 'user' | 'assistant'; text: string; content?: FaqContent | null; sourceTitle?: string; sourcePath?: string; cards?: FaqCard[]; sources?: FaqSource[]; total?: number | null; page?: number | null; retrievedAt?: string | null; sections?: FaqAnswer[] };
const focusClass = 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 dark:focus-visible:ring-offset-slate-900';

/** Only current questions are uploaded; private tool results stay server-rendered data. */
function FaqChatbotPanel({ language, personal }: { language: 'vi' | 'en'; personal: boolean }) {
  const en = language === 'en';
  const t = (vi: string, english: string) => en ? english : vi;
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [suggestions, setSuggestions] = useState<FaqSuggestion[]>([]);
  const [suggestionError, setSuggestionError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const launcher = useRef<HTMLButtonElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const transcript = useRef<HTMLDivElement>(null);
  const current = useRef<AbortController | null>(null);
  const conversationId = useRef<string | undefined>(undefined);
  const [contextNotice, setContextNotice] = useState(false);

  useEffect(() => () => {
    current.current?.abort();
    if (conversationId.current) void chatbotService.clear(conversationId.current, personal).catch(() => {});
  }, [personal]);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    input.current?.focus({ preventScroll: true });
    setSuggestionError(false);
    chatbotService.suggestions(language, controller.signal).then(response => {
      if (!controller.signal.aborted) setSuggestions(response.data);
    }).catch(() => { if (!controller.signal.aborted) setSuggestionError(true); });
    return () => controller.abort();
  }, [open, language]);
  useEffect(() => {
    // Scroll the transcript only; never scroll the page or change route/query params.
    if (transcript.current) transcript.current.scrollTop = transcript.current.scrollHeight;
  }, [messages, busy]);

  const cancelRequest = () => {
    current.current?.abort(); current.current = null; setBusy(false);
  };
  const close = () => {
    cancelRequest(); setOpen(false); launcher.current?.focus({ preventScroll: true });
  };
  const clear = () => {
    if (conversationId.current) void chatbotService.clear(conversationId.current, personal).catch(() => {});
    conversationId.current = undefined;
    setContextNotice(false);
    cancelRequest(); setMessages([]); setDraft(''); setError(''); input.current?.focus({ preventScroll: true });
  };
  const send = async (text: string) => {
    const question = text.trim();
    if (!question || question.length > 500 || current.current) return;
    const controller = new AbortController(); current.current = controller;
    setBusy(true); setError(''); setDraft('');
    setMessages(previous => [...previous, { role: 'user' as const, text: question }].slice(-39));
    try {
      if (!conversationId.current) conversationId.current = crypto.randomUUID();
      const response = await chatbotService.ask(question, language, controller.signal, personal, conversationId.current);
      if (controller.signal.aborted || current.current !== controller) return;
      const answer: FaqAnswer = response.data;
      if (answer.conversationId) conversationId.current = answer.conversationId;
      setContextNotice(!!answer.contextReset && messages.length > 0);
      setMessages(previous => [...previous, { role: 'assistant' as const, text: answer.answer,
        content: answer.content, sourceTitle: answer.sourceTitle, sourcePath: answer.sourcePath,
        cards: answer.cards, sources: answer.sources, total: answer.total, page: answer.page, retrievedAt: answer.retrievedAt, sections: answer.sections }].slice(-40));
    } catch (reason: unknown) {
      if (controller.signal.aborted || current.current !== controller) return;
      const failure = reason as { status?: number; response?: { status?: number } };
      const status = failure?.response?.status ?? failure?.status;
      const authExpired = personal && (status === 401 || status === 403);
      if (authExpired) setMessages([]);
      setError(authExpired
        ? t('Phiên tra cứu đã hết hiệu lực. Bạn hãy đăng nhập lại.', 'Your lookup session is no longer valid. Please sign in again.')
        : status === 429
        ? t('Trợ lý đang nhận nhiều câu hỏi. Vui lòng thử lại sau một phút.', 'The assistant is receiving many questions. Please try again in a minute.')
        : t('Chưa gửi được câu hỏi. Bạn có thể thử lại hoặc mở hướng dẫn.', 'The question could not be sent. Please retry or open the guide.'));
      setDraft(question);
    } finally {
      if (current.current === controller) { current.current = null; setBusy(false); }
    }
  };
  const submit = (event: FormEvent) => { event.preventDefault(); void send(draft); };

  return <>
    {open && <section id="library74-faq-chat" role="dialog" aria-modal="false" aria-labelledby="faq-chat-title"
      onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); close(); } }}
      className="fixed bottom-24 left-3 right-3 z-40 flex h-[min(36rem,calc(100dvh-8rem))] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white text-slate-900 shadow-2xl sm:left-auto sm:right-6 sm:w-[25rem] dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
      <header className="flex shrink-0 items-center gap-3 border-b border-slate-200 bg-blue-50 px-4 py-3 dark:border-slate-700 dark:bg-slate-800">
        <BookOpen aria-hidden="true" size={24} className="text-blue-700 dark:text-blue-300" />
        <div className="min-w-0 flex-1"><h2 id="faq-chat-title" className="font-bold">{t('Trợ lý hướng dẫn Library74', 'Library74 help assistant')}</h2></div>
        <button type="button" onClick={clear} aria-label={t('Xóa hội thoại', 'Clear conversation')} className={`rounded-lg p-2.5 hover:bg-blue-100 dark:hover:bg-slate-700 ${focusClass}`}><Trash2 aria-hidden="true" size={18} /></button>
        <button type="button" onClick={close} aria-label={t('Đóng trợ lý', 'Close assistant')} className={`rounded-lg p-2.5 hover:bg-blue-100 dark:hover:bg-slate-700 ${focusClass}`}><X aria-hidden="true" size={18} /></button>
      </header>
      <div ref={transcript} role="log" aria-label={t('Hội thoại hướng dẫn', 'Help conversation')} aria-live="polite" aria-relevant="additions" className="min-h-0 flex-1 space-y-3 overflow-y-auto overscroll-contain p-4">
        <p className="rounded-xl bg-slate-100 p-3 text-sm leading-relaxed dark:bg-slate-800">{t('Xin chào! Tôi có thể hướng dẫn sử dụng, tìm sách và tra cứu thông tin Library74. Bạn đọc đăng nhập có thể xem dữ liệu của chính mình.', 'Hello! I can help you use Library74, find books and look up library information. Signed-in readers can view their own records.')}</p>
        {messages.map((message, index) => <div key={index} className={`max-w-[95%] rounded-xl p-3 text-sm leading-relaxed ${message.role === 'user' ? 'ml-auto bg-blue-600 text-white' : 'bg-slate-100 dark:bg-slate-800'}`}>
          <span className="sr-only">{message.role === 'user' ? t('Bạn: ', 'You: ') : t('Trợ lý: ', 'Assistant: ')}</span>
          <ChatbotAnswerBody text={message.text} content={message.role === 'assistant' ? message.content : undefined} />
          {message.sections?.map((section, part) => <div key={part} className="mt-3 space-y-2 border-t border-slate-300 pt-3 first:mt-0 first:border-0 first:pt-0 dark:border-slate-600">
            {section.sourceTitle && <h3 className="font-semibold">{section.sourceTitle}</h3>}
            <ChatbotAnswerBody text={section.answer} content={section.content} />
            {section.cards && <ChatbotCards cards={section.cards} language={language} />}
            {section.total != null && <p className="text-xs text-slate-500 dark:text-slate-400">{t('Tổng kết quả', 'Total results')}: {section.total} · {t('Trang', 'Page')} {(section.page ?? 0) + 1}</p>}
            {section.retrievedAt && <p className="text-xs text-slate-500 dark:text-slate-400">{t('Tra cứu lúc', 'Retrieved at')}: {new Date(section.retrievedAt).toLocaleString(en ? 'en-GB' : 'vi-VN')}</p>}
            {section.sources?.filter(source => safeChatPath(source.path)).map((source, sourceIndex) => <Link key={sourceIndex} to={source.path} onClick={close} className="block text-xs text-blue-700 underline dark:text-blue-300">{source.title}</Link>)}
          </div>)}
          {message.cards && message.cards.length > 0 && <ChatbotCards cards={message.cards} language={language} />}
          {message.total != null && <p className="mt-2 text-xs text-slate-600 dark:text-slate-300">
            {t('Tổng kết quả', 'Total results')}: {message.total} · {t('Trang', 'Page')} {(message.page ?? 0) + 1}
          </p>}
          {message.retrievedAt && <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
            {t('Tra cứu lúc', 'Retrieved at')}: {new Date(message.retrievedAt).toLocaleString(en ? 'en-GB' : 'vi-VN')}
          </p>}
          {message.sources && message.sources.length > 1 && message.sources.filter(source => safeChatPath(source.path) && source.path !== message.sourcePath).map((source, sourceIndex) =>
            <Link key={sourceIndex} to={source.path} onClick={close} className="mt-2 block text-xs text-blue-700 underline dark:text-blue-300">{source.title}</Link>)}
          {safeChatPath(message.sourcePath) && <Link to={message.sourcePath!} onClick={close} className={`mt-2 inline-block rounded text-xs font-semibold text-blue-700 underline dark:text-blue-300 ${focusClass}`}>
            {t('Xem hướng dẫn: ', 'Read guide: ')}{message.sourceTitle}
          </Link>}
        </div>)}
        {busy && <p role="status" className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300"><Loader2 aria-hidden="true" size={16} className="motion-safe:animate-spin" />{t('Đang suy nghĩ…', 'Thinking…')}</p>}
      </div>
      <div className="shrink-0 space-y-2 border-t border-slate-200 px-4 py-3 dark:border-slate-700">
        {contextNotice && <p role="status" className="text-xs text-amber-800 dark:text-amber-300">{t('Ngữ cảnh trước đó đã hết hạn hoặc chưa lưu được. Bạn hãy nhắc lại tên sách nếu cần.', 'Previous context expired or could not be saved. Please repeat the book title if needed.')}</p>}
        {suggestionError && <p role="status" className="text-xs text-amber-800 dark:text-amber-300">{t('Chưa tải được câu hỏi gợi ý. Bạn có thể nhập câu hỏi hoặc mở hướng dẫn.', 'Suggested questions could not be loaded. Type a question or open the guide.')}</p>}
        {suggestions.length > 0 && <><label htmlFor="faq-chat-topics" className="sr-only">{t('Chọn câu hỏi gợi ý', 'Choose a suggested question')}</label>
          <select id="faq-chat-topics" value="" disabled={busy} onChange={event => { if (event.target.value) void send(event.target.value); }}
            className={`w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm disabled:opacity-50 dark:border-slate-600 dark:bg-slate-800 ${focusClass}`}>
            <option value="">{t('Chọn câu hỏi gợi ý…', 'Choose a suggested question…')}</option>
            {suggestions.map(item => <option key={item.id} value={item.question}>{item.question}</option>)}
          </select></>}
        {error && <p role="alert" className="text-xs text-rose-700 dark:text-rose-300">{error}</p>}
        <form onSubmit={submit} className="flex items-end gap-2">
          <label htmlFor="faq-chat-question" className="sr-only">{t('Câu hỏi về Library74', 'Question about Library74')}</label>
          <textarea ref={input} id="faq-chat-question" rows={2} maxLength={500} value={draft} onChange={event => setDraft(event.target.value)}
            placeholder={t('Ví dụ: Làm cách nào để mượn sách?', 'For example: How can I borrow a book?')}
            onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(draft); } }}
            className={`min-w-0 flex-1 resize-none rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm placeholder:text-slate-500 dark:border-slate-600 dark:bg-slate-800 dark:placeholder:text-slate-400 ${focusClass}`} />
          <button type="submit" disabled={busy || !draft.trim()} aria-label={t('Gửi câu hỏi', 'Send question')} className={`rounded-lg bg-blue-600 p-3 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50 ${focusClass}`}><Send aria-hidden="true" size={20} /></button>
        </form>
        <div className="flex justify-between text-xs text-slate-600 dark:text-slate-300"><Link to="/publicpage/guide" onClick={close} className={`rounded underline ${focusClass}`}>{t('Mở hướng dẫn', 'Open user guide')}</Link><span>{draft.length}/500</span></div>
      </div>
    </section>}
    <button ref={launcher} type="button" onClick={() => open ? close() : setOpen(true)} aria-label={open ? t('Ẩn trợ lý hướng dẫn', 'Hide help assistant') : t('Mở trợ lý hướng dẫn', 'Open help assistant')}
      aria-expanded={open} aria-controls="library74-faq-chat"
      className={`fixed bottom-6 right-4 z-40 flex h-14 w-14 items-center justify-center rounded-full bg-blue-700 text-white shadow-lg hover:bg-blue-800 sm:right-6 ${focusClass}`}>
      <MessageCircle aria-hidden="true" size={26} />
    </button>
  </>;
}

export default function FaqChatbot() {
  const { language } = useLanguage();
  const { uploads } = useUpload();
  const { userType, sessionVersion } = useAuth();
  // The existing upload panel occupies this corner and takes priority over help.
  if (uploads.length > 0) return null;
  // Language changes discard pending requests and local conversation instead of mixing responses.
  return <FaqChatbotPanel key={`${language}:${userType ?? 'guest'}:${sessionVersion ?? 0}`} language={language} personal={userType === 'student'} />;
}
