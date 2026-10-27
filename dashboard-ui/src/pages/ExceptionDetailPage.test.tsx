import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import { setOperator } from '../hooks/useOperator';
import { mockFetch } from '../test/fakes';
import { detail } from '../test/fixtures';
import ExceptionDetailPage from './ExceptionDetailPage';

function renderAt(url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/exceptions/:id" element={<ExceptionDetailPage />} />
          <Route path="/exceptions" element={<p>list page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const resolved = { ...detail, status: 'RESOLVED', resolvedBy: 'asha.k', resolvedAt: '2026-10-06T07:00:00Z', resolutionNote: 'Bank confirmed' } as const;

describe('ExceptionDetailPage', () => {
  beforeEach(() => {
    localStorage.clear();
    act(() => setOperator(''));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('lays the sources side by side, flags the fields that differ and shows a missing source', async () => {
    mockFetch({ '/exceptions/7': { body: detail } });
    renderAt('/exceptions/7');

    expect(await screen.findByRole('heading', { name: /TXN-1001/ })).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Field', 'LEDGER', 'PSP', 'BANK']);

    const amountRow = within(table).getByRole('row', { name: /Amount/ });
    expect(amountRow).toHaveClass('differs');
    expect(within(amountRow).getByText('100.00')).toBeInTheDocument();
    expect(within(amountRow).getByText('99.50')).toBeInTheDocument();
    expect(within(amountRow).getByText('No event')).toBeInTheDocument(); // BANK never reported

    expect(within(table).getByRole('row', { name: /Currency/ })).not.toHaveClass('differs');
    expect(screen.getByText('AmountMismatchRule')).toBeInTheDocument();
    expect(screen.getByText('Amount by source')).toBeInTheDocument();
  });

  it('keeps the resolve button off until a name and a note are given, then resolves and shows the audit trail', async () => {
    let state: unknown = detail;
    const fetchMock = mockFetch({
      '/exceptions/7/resolve': () => { state = resolved; return { body: resolved }; },
      '/exceptions/7': () => ({ body: state }),
    });
    renderAt('/exceptions/7');
    const button = await screen.findByRole('button', { name: 'Resolve exception' });
    expect(button).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Your name'), 'asha.k');
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('What was done'), '  Bank confirmed  ');
    expect(button).toBeEnabled();
    await userEvent.click(button);

    expect(await screen.findByRole('heading', { name: 'Resolved' })).toBeInTheDocument();
    expect(screen.getByText('Bank confirmed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve exception' })).not.toBeInTheDocument();

    const post = fetchMock.mock.calls.find((c) => (c[1] as RequestInit | undefined)?.method === 'POST')!;
    expect(JSON.parse((post[1] as RequestInit).body as string)).toEqual({ resolvedBy: 'asha.k', note: 'Bank confirmed' });
    expect(localStorage.getItem('recon.operator')).toBe('asha.k'); // remembered for next time
  });

  it('prefills the remembered name', async () => {
    act(() => setOperator('ravi.s'));
    mockFetch({ '/exceptions/7': { body: detail } });
    renderAt('/exceptions/7');
    expect(await screen.findByLabelText('Your name')).toHaveValue('ravi.s');
  });

  it('handles someone else resolving it first: explains and reloads their resolution', async () => {
    let reads = 0;
    mockFetch({
      '/exceptions/7/resolve': { status: 409, body: { detail: 'Exception 7 is already RESOLVED' } },
      '/exceptions/7': () => (reads++ === 0 ? { body: detail } : { body: resolved }),
    });
    renderAt('/exceptions/7');
    await userEvent.type(await screen.findByLabelText('Your name'), 'ravi.s');
    await userEvent.type(screen.getByLabelText('What was done'), 'fixed');
    await userEvent.click(screen.getByRole('button', { name: 'Resolve exception' }));

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Resolved' })).toBeInTheDocument());
    expect(screen.getByText('asha.k')).toBeInTheDocument();
  });

  it('shows a resolved exception read-only', async () => {
    mockFetch({ '/exceptions/7': { body: resolved } });
    renderAt('/exceptions/7');
    expect(await screen.findByRole('heading', { name: 'Resolved' })).toBeInTheDocument();
    expect(screen.queryByLabelText('What was done')).not.toBeInTheDocument();
  });

  it('explains an unknown or invalid exception number', async () => {
    mockFetch({ '/exceptions/404': { status: 404, body: { detail: 'Exception 404 not found' } } });
    renderAt('/exceptions/404');
    expect(await screen.findByText(/Exception 404 does not exist/)).toBeInTheDocument();

    renderAt('/exceptions/abc');
    expect(await screen.findByText(/not valid/)).toBeInTheDocument();
  });
});
