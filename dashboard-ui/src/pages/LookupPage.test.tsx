import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockFetch } from '../test/fakes';
import LookupPage from './LookupPage';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LookupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe('LookupPage', () => {
  it('shows status, cause and where the answer came from', async () => {
    const fetchMock = mockFetch({
      '/transactions/TXN-1001': {
        body: {
          transactionRef: 'TXN-1001', status: 'EXCEPTION', exceptionCode: 'AMOUNT_MISMATCH',
          matchedAt: null, updatedAt: '2026-10-06T08:00:00Z', sources: ['LEDGER', 'PSP'], servedFrom: 'CACHE',
        },
      },
    });
    renderPage();

    const button = screen.getByRole('button', { name: 'Find transaction' });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Transaction reference'), '  TXN-1001 ');
    await userEvent.click(button);

    expect(await screen.findByRole('heading', { name: /TXN-1001/ })).toBeInTheDocument();
    expect(screen.getByText('Exception')).toBeInTheDocument();
    expect(screen.getByText('Amount mismatch')).toBeInTheDocument();
    expect(screen.getByText('LEDGER, PSP')).toBeInTheDocument();
    expect(screen.getByText('Read from the live cache.')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/transactions/TXN-1001'); // trimmed
  });

  it('explains an unknown reference instead of showing a raw error', async () => {
    mockFetch({ '/transactions/NOPE': { status: 404, body: { detail: 'Transaction NOPE not found' } } });
    renderPage();

    await userEvent.type(screen.getByLabelText('Transaction reference'), 'NOPE');
    await userEvent.click(screen.getByRole('button', { name: 'Find transaction' }));

    expect(await screen.findByText(/No transaction found for “NOPE”/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
