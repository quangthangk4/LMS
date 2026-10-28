import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useLanguage } from '../contexts/LanguageContext';
import { helpService, HelpTopic } from '../api/helpService';
import circulationPolicyService from '../api/circulationPolicyService';
import type { CirculationPolicy } from '../api/adminService';
import { ChatbotAnswerBody } from './ChatbotAnswerBody';

function useHelpAnchor(ready: boolean) {
  const { hash } = useLocation();
  const id = /^#[a-z][a-z_-]*$/.test(hash) ? hash.slice(1) : '';
  useEffect(() => {
    if (!ready || !id) return;
    const element = document.getElementById(id);
    if (!element) return;
    if (element instanceof HTMLDetailsElement) element.open = true;
    element.scrollIntoView?.({ block: 'start', behavior: 'auto' });
    element.focus({ preventScroll: true });
  }, [ready, id]);
  return id;
}

export function useHelpContent() {
  const { language } = useLanguage();
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<{ language: string; topics: HelpTopic[]; loading: boolean; error: boolean }>({ language, topics: [], loading: true, error: false });
  useEffect(() => {
    const controller = new AbortController();
    setState({ language, topics: [], loading: true, error: false });
    helpService.content(language, controller.signal).then(response => {
      if (!controller.signal.aborted) setState({ language, topics: response.data.topics, loading: false, error: false });
    }).catch(() => { if (!controller.signal.aborted) setState({ language, topics: [], loading: false, error: true }); });
    return () => controller.abort();
  }, [language, attempt]);
  return { topics: state.language === language ? state.topics : [], loading: state.language !== language || state.loading,
    error: state.language === language && state.error, retry: () => setAttempt(value => value + 1) };
}

function ContentState({ loading, error, retry }: { loading: boolean; error: boolean; retry: () => void }) {
  const { language } = useLanguage();
  const en = language === 'en';
  if (loading) return <p role="status" className="py-4 text-sm text-slate-600 dark:text-slate-300">{en ? 'Loading guidance…' : 'Đang tải hướng dẫn…'}</p>;
  if (!error) return null;
  return <div role="alert" className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
    <p>{en ? 'The current guidance could not be loaded.' : 'Chưa tải được nội dung hướng dẫn hiện tại.'}</p>
    <button onClick={retry} className="mt-2 rounded font-semibold underline focus-visible:ring-2 focus-visible:ring-blue-500">{en ? 'Try again' : 'Thử lại'}</button>
  </div>;
}

export function HelpTopicSnippet({ topicId }: { topicId: string }) {
  const content = useHelpContent();
  const topic = content.topics.find(item => item.id === topicId);
  useHelpAnchor(!!topic);
  return <section id={topicId} tabIndex={-1} className="scroll-mt-24 rounded-xl border border-slate-200 bg-white p-5 text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100">
    <ContentState {...content} />
    {topic && <><h2 className="mb-3 text-lg font-bold">{topic.question}</h2><ChatbotAnswerBody text="" content={topic.content} /></>}
  </section>;
}

function CurrentPolicy() {
  const { language } = useLanguage();
  const en = language === 'en';
  const [policy, setPolicy] = useState<CirculationPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(false); setPolicy(null);
    circulationPolicyService.getPolicy(controller.signal).then(response => {
      if (!controller.signal.aborted) { setPolicy(response.data); setLoading(false); }
    }).catch(() => { if (!controller.signal.aborted) { setError(true); setLoading(false); } });
    return () => controller.abort();
  }, [attempt]);
  const amount = (value: number | null | undefined) => value == null ? '—' : `${Number(value).toLocaleString('vi-VN')}đ`;
  const rows = policy ? [
    [en ? 'Loan period (days)' : 'Thời hạn mượn (ngày)', policy.defaultLoanDays],
    [en ? 'Maximum active loans' : 'Số sách đang mượn tối đa', policy.maxActiveBorrows],
    [en ? 'Maximum reservations' : 'Số lượt đặt trước tối đa', policy.maxActiveReservations],
    [en ? 'Maximum renewals per loan' : 'Số lượt gia hạn mỗi lần mượn', policy.maxRenewals],
    [en ? 'Renewal window (days)' : 'Mở gia hạn trước hạn (ngày)', policy.renewalWindowDays],
    [en ? 'Pickup deadline (hours)' : 'Hạn nhận sách (giờ)', policy.pickupDeadlineHours],
    [en ? 'Deposit' : 'Tiền cọc', amount(policy.defaultDepositAmount)],
    [en ? 'Overdue fine per day' : 'Phí trễ hạn mỗi ngày', amount(policy.overdueFinePerDay)],
    [en ? 'Unpaid fines block borrowing' : 'Chặn mượn khi còn nợ phạt', policy.blockBorrowWhenUnpaidFines ? (en ? 'Yes' : 'Có') : (en ? 'No' : 'Không')],
  ] : [];
  return <section id="current-policy" tabIndex={-1} aria-label={en ? 'Current circulation policy' : 'Chính sách mượn trả hiện hành'} className="mb-6 scroll-mt-24 rounded-2xl border border-blue-200 bg-blue-50 p-5 dark:border-blue-500/30 dark:bg-blue-500/10">
    <h2 className="mb-4 text-xl font-bold">{en ? 'Current circulation policy' : 'Chính sách mượn trả hiện hành'}</h2>
    <ContentState loading={loading} error={error} retry={() => setAttempt(value => value + 1)} />
    {policy && <dl className="grid gap-4 sm:grid-cols-2">{rows.map(([label, value]) => <div key={String(label)}>
      <dt className="text-sm text-slate-600 dark:text-slate-300">{label}</dt><dd className="mt-1 font-semibold">{value ?? '—'}</dd>
    </div>)}</dl>}
  </section>;
}

export default function HelpCenter({ kind }: { kind: 'guide' | 'faq' }) {
  const { language } = useLanguage();
  const en = language === 'en';
  const content = useHelpContent();
  const [query, setQuery] = useState('');
  const target = useHelpAnchor(!content.loading && !content.error);
  useEffect(() => { if (target) setQuery(''); }, [target]);
  const title = kind === 'guide' ? (en ? 'User Guide' : 'Hướng dẫn sử dụng') : (en ? 'Frequently asked questions' : 'Câu hỏi thường gặp');
  const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').toLowerCase();
  const topics = content.topics.filter(topic => (kind === 'faq' || !['creator', 'about'].includes(topic.id))
    && (topic.id === target || normalize(`${topic.question} ${topic.content.summary} ${topic.content.steps.join(' ')} ${topic.content.bullets.join(' ')} ${topic.content.note}`).includes(normalize(query))));
  return <div className="min-h-screen bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
    <header className="bg-gradient-to-r from-blue-700 to-indigo-700 px-4 py-12 text-white"><div className="mx-auto max-w-4xl">
      <h1 className="text-3xl font-bold sm:text-4xl">{title}</h1>
      <p className="mt-3 text-blue-100">{en ? 'Guidance and current public rules for using Library74.' : 'Hướng dẫn và quy định công khai hiện hành để sử dụng Library74.'}</p>
    </div></header>
    <main className="mx-auto max-w-4xl px-4 py-8">
      {kind === 'faq' && <CurrentPolicy />}
      <label htmlFor={`help-${kind}-search`} className="mb-2 block text-sm font-medium">{en ? 'Find a topic' : 'Tìm chủ đề'}</label>
      <input id={`help-${kind}-search`} type="search" value={query} onChange={event => setQuery(event.target.value)}
        className="mb-6 w-full rounded-xl border border-slate-300 bg-white px-4 py-3 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:border-slate-600 dark:bg-slate-900" />
      <ContentState {...content} />
      <div className="space-y-4">{topics.map(topic => <details key={`${language}-${topic.id}`} id={topic.id} tabIndex={-1} open={topic.id === target || undefined} className="group scroll-mt-24 rounded-xl border border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900">
        <summary className="cursor-pointer rounded-xl px-5 py-4 font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500">{topic.question}</summary>
        <div className="border-t border-slate-200 px-5 py-4 text-sm leading-relaxed dark:border-slate-700"><ChatbotAnswerBody text="" content={topic.content} /></div>
      </details>)}</div>
      {!content.loading && !content.error && topics.length === 0 && <p role="status">{en ? 'No matching guidance was found.' : 'Chưa tìm thấy chủ đề phù hợp.'}</p>}
      <p className="mt-8 text-sm"><Link to="/publicpage/contact" className="rounded font-semibold text-blue-700 underline focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">{en ? 'Contact the library for help' : 'Liên hệ thư viện để được hỗ trợ'}</Link></p>
    </main>
  </div>;
}
