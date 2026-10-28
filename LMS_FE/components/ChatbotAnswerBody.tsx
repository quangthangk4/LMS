import type { FaqContent } from '../api/chatbotService';

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
