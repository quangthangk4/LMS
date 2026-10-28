import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { FaqAnswer, FaqCard, FaqContent } from '../api/chatbotService';

export const safeChatPath = (path?: string) => !!path && (
  /^\/publicpage\/(guide|faq|about|contact|privacy-policy|login|search)(#[a-z][a-z_-]*)?$/.test(path)
  || /^\/publicpage\/book\/[1-9][0-9]*$/.test(path)
  || /^\/userpage\/(my-books|fines|reservations|wishlist|notifications)$/.test(path)
);
const safeImage = (url?: string | null) => !!url && (/^https?:\/\/[^\s]+$/i.test(url)
  || (url.startsWith('/') && !url.startsWith('//') && !url.includes('\\')));

export function ChatbotAnswerBody({ text, content }: { text: string; content?: FaqContent | null }) {
  if (!content || typeof content.summary !== 'string' || typeof content.note !== 'string'
    || !Array.isArray(content.steps) || !content.steps.every(step => typeof step === 'string')
    || !Array.isArray(content.bullets) || !content.bullets.every(bullet => typeof bullet === 'string')) {
    return <p className="whitespace-pre-wrap break-words">{text}</p>;
  }
  return <div className="space-y-3 break-words">
    {content.summary && <p>{content.summary}</p>}
    {content.steps.length > 0 && <ol className="list-decimal space-y-2 pl-5 marker:font-semibold marker:text-blue-700 dark:marker:text-blue-300">
      {content.steps.map((step, index) => <li key={index} className="pl-1">{step}</li>)}
    </ol>}
    {content.bullets.length > 0 && <ul className="list-disc space-y-2 pl-5">
      {content.bullets.map((bullet, index) => <li key={index}>{bullet}</li>)}
    </ul>}
    {content.note && <p className="border-l-2 border-blue-400 pl-3 text-slate-600 dark:text-slate-300">{content.note}</p>}
  </div>;
}

export function ChatbotCards({ cards, language }: { cards: FaqCard[]; language: 'vi' | 'en' }) {
  const en = language === 'en';
  const [expanded, setExpanded] = useState(false);
  const grouped = new Map<string, FaqCard>();
  for (const card of cards) {
    const key = /^\/publicpage\/book\//.test(card.path) ? card.path : `${card.path}:${card.id}`;
    const previous = grouped.get(key);
    if (!previous) grouped.set(key, { ...card, facts: card.facts.map(fact => ({ ...fact })) });
    else {
      previous.subtitle ||= card.subtitle;
      previous.imageUrl ||= card.imageUrl;
      for (const fact of card.facts) {
        const found = previous.facts.find(f => f.label === fact.label);
        if (!found) previous.facts.push({ ...fact });
        else if (!found.value.split(' · ').includes(fact.value)) found.value += ` · ${fact.value}`;
      }
    }
  }
  const books = [...grouped.values()];
  const format = (value: string) => {
    const date = /^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$/.exec(value);
    return date ? `${date[3]}/${date[2]}/${date[1]}` : value;
  };
  return <div className="mt-3 space-y-2">{books.slice(0, expanded ? books.length : 3).map((card, index) => {
    const facts = card.facts.filter(f => f.value && f.value !== '—');
    const important = facts.filter(f => /^(Hạn trả|Due|Trạng thái|Status|Bản sao khả dụng|Available copies|Cơ sở|Campus|Vị trí kệ|Shelf|Số tiền|Amount)$/.test(f.label));
    const primary = (important.length ? important : facts).slice(0, 2);
    const extra = facts.filter(f => !primary.includes(f));
    return <article key={`${card.id}-${index}`} className="rounded-lg border border-slate-200 bg-white p-2.5 dark:border-slate-600 dark:bg-slate-900">
    <div className="flex items-start gap-2.5">
      {safeImage(card.imageUrl) && <img src={card.imageUrl!} alt="" loading="lazy" referrerPolicy="no-referrer" className="h-14 w-10 shrink-0 rounded object-cover" />}
      <div className="min-w-0 flex-1">
        <h3 className="break-words text-sm font-semibold leading-snug">{card.title}</h3>
        {card.subtitle && card.subtitle !== '—' && <p className="mt-0.5 break-words text-xs text-slate-600 dark:text-slate-300">{card.subtitle}</p>}
        <dl className="mt-1.5 space-y-1 text-xs">{primary.map((fact, field) => <div key={field} className="flex flex-wrap gap-x-1">
          <dt className="text-slate-500 dark:text-slate-400">{fact.label}:</dt><dd className="min-w-0 break-words font-medium">{format(fact.value)}</dd>
        </div>)}</dl>
      </div>
    </div>
    {extra.length > 0 && <details className="mt-2 text-xs"><summary className="cursor-pointer rounded py-1 text-slate-600 focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-slate-300">{en ? 'More information' : 'Thông tin thêm'}</summary>
      <dl className="mt-1 space-y-1.5">{extra.map((fact, field) => <div key={field}>
        <dt className="text-slate-500 dark:text-slate-400">{fact.label}</dt><dd className="whitespace-pre-wrap break-words">{format(fact.value)}</dd>
      </div>)}</dl>
    </details>}
    {safeChatPath(card.path) && <Link to={card.path} target="_blank" rel="noopener noreferrer"
      aria-label={en ? 'Open details in a new tab' : 'Mở chi tiết ở tab mới'}
      className="mt-1.5 inline-block rounded py-1 text-xs font-semibold text-blue-700 underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">
      {en ? 'View details ↗' : 'Xem chi tiết ↗'}
    </Link>}
  </article>; })}
    {!expanded && books.length > 3 && <button type="button" onClick={() => setExpanded(true)} className="min-h-10 rounded-lg px-2 text-xs font-semibold text-blue-700 underline focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">{en ? `Show ${books.length - 3} more` : `Xem thêm ${books.length - 3} sách`}</button>}
  </div>;
}

/** A single rendering path for new answers and older multi-tool payloads. */
export function ChatbotReplyView({ answer, language, onNavigate }: { answer: FaqAnswer; language: 'vi' | 'en'; onNavigate?: () => void }) {
  const en = language === 'en';
  const parts = [answer, ...(answer.sections ?? []).flatMap(section => [section, ...(section.sections ?? [])])];
  const bodies = parts.filter(part => part.answer || part.content?.summary || part.content?.steps?.length);
  const unique = bodies.filter((part, index) => bodies.findIndex(other => JSON.stringify(other.content ?? other.answer) === JSON.stringify(part.content ?? part.answer)) === index);
  const cards = parts.flatMap(part => part.cards ?? []);
  const sources = parts.flatMap(part => [...(part.sources ?? []), ...(part.sourcePath ? [{ title: part.sourceTitle ?? '', path: part.sourcePath }] : [])])
    .filter((source, index, all) => safeChatPath(source.path) && all.findIndex(s => s.path === source.path) === index)
    .filter(source => !cards.some(card => card.path === source.path));
  const retrieved = parts.find(part => part.retrievedAt);
  return <div className="space-y-2.5">
    {unique.map((part, index) => <ChatbotAnswerBody key={index} text={part.answer} content={part.content} />)}
    {cards.length > 0 && <ChatbotCards cards={cards} language={language} />}
    {sources.length > 0 && <div className="flex flex-col gap-1.5 border-t border-slate-200 pt-2 dark:border-slate-700">{sources.map(source => {
      const help = /^\/publicpage\/(guide|faq|about|contact|privacy-policy)(#|$)/.test(source.path);
      const title = source.title && !bodies.some(part => part.answer === source.title) ? source.title
        : source.path.startsWith('/userpage/') ? (en ? 'View my records' : 'Xem thông tin của tôi') : (en ? 'View source' : 'Xem nguồn');
      return <Link key={source.path} to={source.path} onClick={onNavigate} className="break-words rounded text-xs font-medium text-blue-700 underline focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">{help ? (en ? 'Read guide: ' : 'Xem hướng dẫn: ') : ''}{title}</Link>;
    })}</div>}
    {retrieved && <details className="text-xs text-slate-500 dark:text-slate-400"><summary className="cursor-pointer rounded py-1 focus-visible:ring-2 focus-visible:ring-blue-500">{en ? 'Lookup information' : 'Thông tin tra cứu'}</summary>
      {retrieved.total != null && <p>{en ? 'Total results' : 'Tổng kết quả'}: {retrieved.total} · {en ? 'Page' : 'Trang'} {(retrieved.page ?? 0) + 1}</p>}
      <p>{en ? 'Retrieved at' : 'Tra cứu lúc'}: {new Date(retrieved.retrievedAt!).toLocaleString(en ? 'en-GB' : 'vi-VN')}</p>
    </details>}
  </div>;
}
