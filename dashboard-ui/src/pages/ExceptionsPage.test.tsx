import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeEventSource, mockFetch } from '../test/fakes';
import { exceptionRow, pagedList } from '../test/fixtures';
import ExceptionsPage from './ExceptionsPage';

function renderAt(url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/exceptions" element={<ExceptionsPage />} />
          <Route path="/exceptions/:id" element={<p>detail page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const urls = (fn: ReturnType<typeof mockFetch>) => fn.mock.calls.map((c) => String(c[0]));

describe('ExceptionsPage', () => {
  beforeEach(() => {
    FakeEventSource.reset();
    vi.stubGlobal('EventSource', FakeEventSource);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('defaults to open exceptions, honours the cause in the address, and links rows to the drill-down', async () => {
    const fetchMock = mockFetch({
      '/api/v1/exceptions': { body: pagedList([exceptionRow(1), exceptionRow(2, { exceptionCode: 'AMOUNT_MISMATCH' })]) },
    });
    renderAt('/exceptions?code=MISSING_SOURCE');

    expect(await screen.findByRole('link', { name: 'TXN-1' })).toHaveAttribute('href', '/exceptions/1');
    expect(urls(fetchMock)[0]).toContain('status=OPEN');
    expect(urls(fetchMock)[0]).toContain('code=MISSING_SOURCE');
    expect(screen.getByLabelText('Cause')).toHaveValue('MISSING_SOURCE');
    expect(screen.getByText('2 matching')).toBeInTheDocument();
  });

  it('drops the status filter for "All statuses" and resets to the first page', async () => {
    const fetchMock = mockFetch({ '/api/v1/exceptions': { body: pagedList([exceptionRow(1)], { totalPages: 3, totalElements: 50 }) } });
    renderAt('/exceptions?page=2');
    await screen.findByRole('link', { name: 'TXN-1' });

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'ALL');

    await waitFor(() => {
      const last = urls(fetchMock).at(-1)!;
      expect(last).not.toContain('status=');
      expect(last).toContain('page=0');
    });
  });

  it('pages forward and back', async () => {
    const fetchMock = mockFetch({ '/api/v1/exceptions': { body: pagedList([exceptionRow(1)], { totalPages: 3, totalElements: 50 }) } });
    renderAt('/exceptions');
    await screen.findByText('Page 1 of 3');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument();
    expect(urls(fetchMock).at(-1)).toContain('page=1');
  });

  it('says what an empty list means', async () => {
    mockFetch({ '/api/v1/exceptions': { body: pagedList([]) } });
    renderAt('/exceptions');
    expect(await screen.findByText('Nothing is waiting for triage.')).toBeInTheDocument();
  });

  it('offers new live exceptions instead of moving rows, and refreshes when asked', async () => {
    const fetchMock = mockFetch({ '/api/v1/exceptions': { body: pagedList([exceptionRow(1)]) } });
    renderAt('/exceptions?code=MISSING_SOURCE');
    await screen.findByRole('link', { name: 'TXN-1' });
    const before = fetchMock.mock.calls.length;

    act(() => {
      FakeEventSource.latest().emit('exception', exceptionRow(99, { exceptionCode: 'AMOUNT_MISMATCH' })); // other cause: ignored
    });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    act(() => {
      FakeEventSource.latest().emit('exception', exceptionRow(100));
    });
    const banner = await screen.findByRole('status');
    expect(within(banner).getByText(/1 new exception arrived/)).toBeInTheDocument();

    await userEvent.click(within(banner).getByRole('button', { name: 'Show it' }));

    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(before));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('shows the server message and a retry when loading fails', async () => {
    mockFetch({ '/api/v1/exceptions': { status: 500, body: { detail: 'Database unavailable' } } });
    renderAt('/exceptions');
    expect(await screen.findByRole('alert')).toHaveTextContent('Database unavailable');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
