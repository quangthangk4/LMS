import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Link } from 'react-router-dom';

/** Display formatting only. Model Markdown is not rewritten into cards or canned answers. */
export function ChatbotMarkdown({ text, onNavigate }: { text: string; onNavigate?: () => void }) {
  return <div className="min-w-0 space-y-3 break-words [&>*:first-child]:mt-0 [&>*:last-child]:mb-0">
    <Markdown remarkPlugins={[remarkGfm]} skipHtml components={{
      p: ({ children }) => <p className="whitespace-pre-wrap leading-relaxed">{children}</p>,
      h1: ({ children }) => <h3 className="mt-3 text-base font-bold">{children}</h3>,
      h2: ({ children }) => <h3 className="mt-3 font-bold">{children}</h3>,
      h3: ({ children }) => <h3 className="mt-2 font-semibold">{children}</h3>,
      ul: ({ children }) => <ul className="list-disc space-y-1.5 pl-5">{children}</ul>,
      ol: ({ children }) => <ol className="list-decimal space-y-1.5 pl-5 marker:font-semibold">{children}</ol>,
      li: ({ children }) => <li className="pl-0.5 [&>p]:inline">{children}</li>,
      blockquote: ({ children }) => <blockquote className="border-l-2 border-blue-400 pl-3 text-slate-600 dark:text-slate-300">{children}</blockquote>,
      a: ({ href, children }) => href?.startsWith('/') && !href.startsWith('//')
        ? <Link to={href} onClick={onNavigate} className="rounded font-medium text-blue-700 underline underline-offset-2 focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">{children}</Link>
        : <a href={href} target="_blank" rel="noopener noreferrer" className="rounded font-medium text-blue-700 underline underline-offset-2 focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-blue-300">{children}</a>,
      table: ({ children }) => <div className="max-w-full overflow-x-auto"><table className="w-full border-collapse text-xs">{children}</table></div>,
      th: ({ children }) => <th className="border border-slate-300 bg-slate-200/60 px-2 py-1.5 text-left dark:border-slate-600 dark:bg-slate-700">{children}</th>,
      td: ({ children }) => <td className="border border-slate-300 px-2 py-1.5 align-top dark:border-slate-600">{children}</td>,
      pre: ({ children }) => <pre className="max-w-full overflow-x-auto rounded-lg bg-slate-200/70 p-3 text-xs dark:bg-slate-950">{children}</pre>,
      code: ({ children }) => <code className="rounded bg-slate-200/70 px-1 py-0.5 text-[0.9em] dark:bg-slate-700">{children}</code>,
      img: ({ src, alt }) => <img src={src} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" className="max-h-48 max-w-full rounded object-contain" />,
    }}>{text}</Markdown>
  </div>;
}
