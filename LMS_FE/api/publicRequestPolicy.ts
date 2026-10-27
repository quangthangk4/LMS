import type { InternalAxiosRequestConfig } from 'axios';

export const isPublicRequest = (config?: InternalAxiosRequestConfig | any): boolean => {
  const method = String(config?.method || 'get').toLowerCase();
  const rawUrl = String(config?.url || '');

  let pathname = rawUrl;
  try {
    pathname = new URL(rawUrl, 'http://library74.local/api/v1').pathname;
    pathname = pathname.replace(/^\/api\/v1/, '');
  } catch {
    pathname = rawUrl.split('?')[0] || '';
  }

  if (method === 'post' && pathname === '/ai/semantic-search') return true;
  if (method === 'post' && pathname === '/chatbot/messages') return true;
  if (method === 'delete' && /^\/chatbot\/conversations\/[0-9a-f-]{36}$/i.test(pathname)) return true;

  if (method !== 'get') return false;
  if (pathname === '/chatbot/faqs') return true;
  if (pathname === '/help/content') return true;
  if (pathname === '/circulation-policies') return true;

  if (pathname === '/publications/librarian') return false;
  if (pathname === '/publications/book-lookup') return false;
  if (/^\/publications\/[^/]+\/document-upload-url$/.test(pathname)) return false;
  if (pathname === '/categories/search') return false;
  if (pathname.startsWith('/authors')) return false;
  if (pathname.startsWith('/publishers')) return false;
  if (pathname.startsWith('/tags')) return false;

  if (pathname === '/publications/search') return true;
  if (pathname === '/publications/newest') return true;
  if (pathname === '/publications/most-borrowed') return true;
  if (pathname === '/publications/public-stats') return true;
  if (pathname === '/publications/testimonials') return true;

  if (/^\/publications\/[^/]+$/.test(pathname)) return true;
  if (/^\/publications\/[^/]+\/items$/.test(pathname)) return true;
  if (/^\/publications\/[^/]+\/ratings$/.test(pathname)) return true;
  if (/^\/publications\/[^/]+\/ratings\/summary$/.test(pathname)) return true;
  if (/^\/publications\/[^/]+\/similar$/.test(pathname)) return true;

  return (
    pathname === '/categories' ||
    pathname.startsWith('/system-reviews')
  );
};
