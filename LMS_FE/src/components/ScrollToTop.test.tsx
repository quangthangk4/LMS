import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { ScrollToTop } from '../../components/ScrollToTop';

const RoutedContent = () => (
  <>
    <ScrollToTop />
    <nav>
      <Link to="/publicpage/search">Search</Link>
      <Link to="/publicpage?transactionId=1">Open drawer</Link>
      <Link to="/publicpage">Close drawer</Link>
      <Link to="/publicpage#password">Help topic</Link>
    </nav>
    <main data-route-scroll-container data-testid="scroll-container">
      <Routes>
        <Route path="/publicpage" element={<div style={{ height: 2000 }}>Home</div>} />
        <Route path="/publicpage/search" element={<div style={{ height: 2000 }}>Search</div>} />
      </Routes>
    </main>
  </>
);

describe('ScrollToTop', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    window.scrollTo = jest.fn();
  });

  afterEach(() => {
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
    jest.restoreAllMocks();
  });
  it('does not let pending route timers override a newly selected anchor', () => {
    render(<MemoryRouter initialEntries={['/publicpage']}><RoutedContent /></MemoryRouter>);
    fireEvent.click(screen.getByText('Help topic'));
    const container = screen.getByTestId('scroll-container');
    container.scrollTop = 900;
    window.scrollTo = jest.fn();
    act(() => { jest.advanceTimersByTime(700); });
    expect(container.scrollTop).toBe(900);
    expect(window.scrollTo).not.toHaveBeenCalled();
  });

  it('resets the active app scroll container after a route change', () => {
    render(
      <MemoryRouter initialEntries={['/publicpage']}>
        <RoutedContent />
      </MemoryRouter>
    );

    const container = screen.getByTestId('scroll-container');
    container.scrollTop = 1200;

    fireEvent.click(screen.getByText('Search'));

    expect(container.scrollTop).toBe(0);

    container.scrollTop = 900;
    act(() => {
      jest.advanceTimersByTime(700);
    });

    expect(container.scrollTop).toBe(0);
  });

  it('preserves scroll for query changes and a new history entry at the same pathname', () => {
    render(<MemoryRouter initialEntries={['/publicpage']}><RoutedContent /></MemoryRouter>);
    act(() => { jest.advanceTimersByTime(700); });
    const container = screen.getByTestId('scroll-container');
    container.scrollTop = 1200;
    jest.mocked(window.scrollTo).mockClear();
    fireEvent.click(screen.getByText('Open drawer'));
    act(() => { jest.advanceTimersByTime(700); });
    expect(container.scrollTop).toBe(1200);
    fireEvent.click(screen.getByText('Close drawer'));
    fireEvent.click(screen.getByText('Close drawer'));
    act(() => { jest.advanceTimersByTime(700); });
    expect(container.scrollTop).toBe(1200);
    expect(window.scrollTo).not.toHaveBeenCalled();
  });
});
