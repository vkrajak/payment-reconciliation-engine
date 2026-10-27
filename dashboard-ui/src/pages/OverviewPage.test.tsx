import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { engineUp, metrics } from '../test/fixtures';
import { FakeEventSource, mockFetch } from '../test/fakes';
import OverviewPage from './OverviewPage';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <OverviewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OverviewPage', () => {
  beforeEach(() => {
    FakeEventSource.reset();
    vi.stubGlobal('EventSource', FakeEventSource);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('shows the day as a tally with lag, causes and engine health', async () => {
    mockFetch({ '/metrics/summary': { body: metrics }, '/engine/health': { body: engineUp } });
    renderPage();

    expect(await screen.findByText('98.20%')).toBeInTheDocument();
    expect(screen.getByText(/out of 1,000 transactions on 6 October 2026/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /970 matched, 12 matched within tolerance, 18 in exception/ })).toBeInTheDocument();

    // lag in readable units
    expect(screen.getByText('900 ms')).toBeInTheDocument();
    expect(screen.getByText('4.2 s')).toBeInTheDocument();
    expect(screen.getByText('1.1 min')).toBeInTheDocument();

    // exceptions by cause link into the triage list
    expect(screen.getByRole('link', { name: 'Missing source' })).toHaveAttribute('href', '/exceptions?code=MISSING_SOURCE');
    expect(screen.getByRole('link', { name: /7 exceptions are open/ })).toHaveAttribute('href', '/exceptions?status=OPEN');

    // engine: degraded, partitions 4 and 5 flagged
    expect(await screen.findByText('Degraded')).toBeInTheDocument();
    expect(screen.getByTitle('Partition 5: no heartbeat')).toHaveClass('partition-missing');
    expect(screen.getByTitle(/Partition 0: /)).toHaveClass('partition-up');
    expect(screen.getByText(/cached for up to 5 minutes/)).toBeInTheDocument();
  });

  it('shows a plain empty state on a day with no transactions', async () => {
    mockFetch({
      '/metrics/summary': { body: { ...metrics, totalTransactions: 0, matched: 0, matchedWithTolerance: 0, exceptions: 0, exceptionsByCode: {}, settlementLagP50Ms: null, settlementLagP95Ms: null, settlementLagP99Ms: null, avgSettlementLagMs: null } },
      '/engine/health': { body: { ...engineUp, status: 'UP', missingPartitions: [] } },
    });
    renderPage();

    expect(await screen.findByText(/No transactions have been reconciled for this date yet/)).toBeInTheDocument();
    expect(screen.getByText(/None matched for this date yet/)).toBeInTheDocument();
    expect(screen.getByText('No exceptions on this date.')).toBeInTheDocument();
  });

  it('says what happened when the API cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the dashboard API');
    expect(await screen.findByText(/Engine status is unavailable/)).toBeInTheDocument();
  });
});
