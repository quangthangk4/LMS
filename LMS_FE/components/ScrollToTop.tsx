import { useLayoutEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

const SCROLL_CONTAINER_SELECTOR = [
  '[data-route-scroll-container]',
  '[data-user-content]',
  '[data-librarian-content]',
  '[data-admin-content]',
  '[data-public-content]',
].join(', ');

export const ScrollToTop = () => {
  const { pathname, hash } = useLocation();
  const currentHash = useRef(hash);
  currentHash.current = hash;

  useLayoutEffect(() => {
    // Anchored help loads asynchronously and scrolls to its own exact topic.
    if (hash) return;
    if ('scrollRestoration' in window.history) {
      window.history.scrollRestoration = 'manual';
    }

    const scrollToTop = () => {
      if (currentHash.current) return;
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;
      document
        .querySelectorAll<HTMLElement>(SCROLL_CONTAINER_SELECTOR)
        .forEach(element => {
          if (typeof element.scrollTo === 'function') {
            element.scrollTo({ top: 0, left: 0, behavior: 'auto' });
          }
          element.scrollTop = 0;
        });
    };

    scrollToTop();
    const frame = window.requestAnimationFrame(scrollToTop);
    const timers = [50, 150, 350, 700].map(delay => window.setTimeout(scrollToTop, delay));

    return () => {
      window.cancelAnimationFrame(frame);
      timers.forEach(window.clearTimeout);
    };
  }, [pathname]);

  return null;
};
